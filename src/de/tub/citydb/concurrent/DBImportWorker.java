package de.tub.citydb.concurrent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

import oracle.jdbc.driver.OracleConnection;
import de.tub.citydb.concurrent.WorkerPool.WorkQueue;
import de.tub.citydb.config.Config;
import de.tub.citydb.config.project.database.Database;
import de.tub.citydb.db.DBConnectionPool;
import de.tub.citydb.db.cache.DBGmlIdLookupServerManager;
import de.tub.citydb.db.importer.DBAppearance;
import de.tub.citydb.db.importer.DBBuilding;
import de.tub.citydb.db.importer.DBCityFurniture;
import de.tub.citydb.db.importer.DBCityObjectGroup;
import de.tub.citydb.db.importer.DBGenericCityObject;
import de.tub.citydb.db.importer.DBImporterEnum;
import de.tub.citydb.db.importer.DBImporterManager;
import de.tub.citydb.db.importer.DBLandUse;
import de.tub.citydb.db.importer.DBPlantCover;
import de.tub.citydb.db.importer.DBReliefFeature;
import de.tub.citydb.db.importer.DBSolitaryVegetatObject;
import de.tub.citydb.db.importer.DBTransportationComplex;
import de.tub.citydb.db.importer.DBWaterBody;
import de.tub.citydb.db.xlink.DBXlink;
import de.tub.citydb.event.EventDispatcher;
import de.tub.citydb.event.info.LogMessageEnum;
import de.tub.citydb.event.info.LogMessageEvent;
import de.tub.citydb.event.statistic.FeatureCounterEvent;
import de.tub.citydb.event.statistic.GeometryCounterEvent;
import de.tub.citydb.event.statistic.TopLevelFeatureCounterEvent;
import de.tub.citydb.filter.ImportFilter;
import de.tub.citydb.filter.feature.BoundingBoxFilter;
import de.tub.citydb.filter.feature.FeatureClassFilter;
import de.tub.citydb.filter.feature.GmlIdFilter;
import de.tub.citydb.filter.feature.GmlNameFilter;
import de.tub.citygml4j.CityGMLFactory;
import de.tub.citygml4j.model.citygml.CityGMLClass;
import de.tub.citygml4j.model.citygml.appearance.Appearance;
import de.tub.citygml4j.model.citygml.building.Building;
import de.tub.citygml4j.model.citygml.cityfurniture.CityFurniture;
import de.tub.citygml4j.model.citygml.cityobjectgroup.CityObjectGroup;
import de.tub.citygml4j.model.citygml.core.CityGMLBase;
import de.tub.citygml4j.model.citygml.core.CityObject;
import de.tub.citygml4j.model.citygml.generics.GenericCityObject;
import de.tub.citygml4j.model.citygml.landuse.LandUse;
import de.tub.citygml4j.model.citygml.relief.ReliefFeature;
import de.tub.citygml4j.model.citygml.transportation.TransportationComplex;
import de.tub.citygml4j.model.citygml.vegetation.PlantCover;
import de.tub.citygml4j.model.citygml.vegetation.SolitaryVegetationObject;
import de.tub.citygml4j.model.citygml.waterbody.WaterBody;
import de.tub.citygml4j.model.gml.AbstractFeature;
import de.tub.citygml4j.model.gml.Code;

public class DBImportWorker implements Worker<CityGMLBase> {
	// instance members needed for WorkPool
	private volatile boolean shouldRun = true;
	private ReentrantLock runLock = new ReentrantLock();
	private WorkQueue<CityGMLBase> workQueue = null;
	private CityGMLBase firstWork;
	private Thread workerThread = null;

	// instance members needed to do work
	private final DBConnectionPool dbConnectionPool;
	private final WorkerPool<DBXlink> tmpXlinkPool;
	private final DBGmlIdLookupServerManager lookupServerManager;
	private final Config config;
	private final EventDispatcher eventDispatcher;
	private final CityGMLFactory cityGMLFactory;
	private final ImportFilter importFilter;
	private Connection batchConn;
	private Connection commitConn;
	private DBImporterManager dbImporterManager;
	private int updateCounter = 0;
	private int commitAfter = 20;

	// filter
	private FeatureClassFilter featureClassFilter;
	private BoundingBoxFilter featureBoundingBoxFilter;
	private GmlIdFilter featureGmlIdFilter;
	private GmlNameFilter featureGmlNameFilter;

	public DBImportWorker(DBConnectionPool dbConnectionPool,
			WorkerPool<DBXlink> tmpXlinkPool,
			DBGmlIdLookupServerManager lookupServerManager,
			CityGMLFactory cityGMLFactory,
			ImportFilter importFilter,
			Config config,
			EventDispatcher eventDispatcher) throws SQLException {
		this.dbConnectionPool = dbConnectionPool;
		this.tmpXlinkPool = tmpXlinkPool;
		this.lookupServerManager = lookupServerManager;
		this.cityGMLFactory = cityGMLFactory;
		this.importFilter = importFilter;
		this.config = config;
		this.eventDispatcher = eventDispatcher;

		init();
	}

	private void init() throws SQLException {
		batchConn = dbConnectionPool.getConnection();
		batchConn.setAutoCommit(false);
		((OracleConnection)batchConn).setImplicitCachingEnabled(true);

		commitConn = dbConnectionPool.getConnection();
		commitConn.setAutoCommit(true);

		// try and change workspace for both connections if needed
		Database database = config.getProject().getDatabase();
		String workspace = database.getWorkspace().getImportWorkspace();
		dbConnectionPool.changeWorkspace(batchConn, workspace);
		dbConnectionPool.changeWorkspace(commitConn, workspace);

		// init filter 
		featureClassFilter = importFilter.getFeatureClassFilter();
		featureBoundingBoxFilter = importFilter.getBoundingBoxFilter();
		featureGmlIdFilter = importFilter.getGmlIdFilter();
		featureGmlNameFilter = importFilter.getGmlNameFilter();		
		
		dbImporterManager = new DBImporterManager(
				batchConn,
				commitConn,
				config,
				tmpXlinkPool,
				lookupServerManager,
				cityGMLFactory,
				eventDispatcher);

		Integer commitAfterProp = database.getUpdateBatching().getFeatureBatchValue();
		if (commitAfterProp != null && commitAfterProp > 0)
			commitAfter = commitAfterProp;
	}

	@Override
	public Thread getThread() {
		return workerThread;
	}

	@Override
	public void interrupt() {
		shouldRun = false;
		workerThread.interrupt();
	}

	@Override
	public void interruptIfIdle() {
		final ReentrantLock runLock = this.runLock;
		shouldRun = false;

		if (runLock.tryLock()) {
			try {
				workerThread.interrupt();
			} finally {
				runLock.unlock();
			}
		}
	}

	@Override
	public void setFirstWork(CityGMLBase firstWork) {
		this.firstWork = firstWork;
	}

	@Override
	public void setThread(Thread workerThread) {
		this.workerThread = workerThread;
	}

	@Override
	public void setWorkQueue(WorkQueue<CityGMLBase> workQueue) {
		this.workQueue = workQueue;
	}

	@Override
	public void run() {
		if (firstWork != null) {
			doWork(firstWork);
			firstWork = null;
		}

		while (shouldRun) {
			try {
				CityGMLBase work = workQueue.take();
				doWork(work);
			} catch (InterruptedException ie) {
				// re-check state
			}
		}

		try {
			dbImporterManager.executeBatch();
			batchConn.commit();

			eventDispatcher.triggerEvent(new TopLevelFeatureCounterEvent(updateCounter));
		} catch (SQLException sqlEx) {
			LogMessageEvent log = new LogMessageEvent(
					"SQL-Fehler: " + sqlEx,
					LogMessageEnum.ERROR);
			eventDispatcher.triggerEvent(log);
		}

		if (batchConn != null) {
			try {
				batchConn.close();
			} catch (SQLException sqlEx) {
				LogMessageEvent log = new LogMessageEvent(
						"SQL-Fehler: " + sqlEx,
						LogMessageEnum.ERROR);
				eventDispatcher.triggerEvent(log);
			}

			batchConn = null;
		}

		if (commitConn != null) {
			try {
				commitConn.close();
			} catch (SQLException sqlEx) {
				LogMessageEvent log = new LogMessageEvent(
						"SQL-Fehler: " + sqlEx,
						LogMessageEnum.ERROR);
				eventDispatcher.triggerEvent(log);
			}

			commitConn = null;
		}

		// propagate the number of features  and geometries this worker
		// did work on...
		eventDispatcher.triggerEvent(new FeatureCounterEvent(dbImporterManager.getFeatureCounter()));
		eventDispatcher.triggerEvent(new GeometryCounterEvent(dbImporterManager.getGeometryCounter()));
	}

	private void doWork(CityGMLBase work) {
		final ReentrantLock runLock = this.runLock;
		runLock.lock();

		try {
			try {
				long id = 0;

				if (work.getCityGMLClass() == CityGMLClass.APPEARANCE) {
					// global appearances
					if (!config.getProject().getImporter().getAppearances().isSetImportAppearance())
						return;

					Appearance appearance = (Appearance)work;

					DBAppearance dbAppearance = (DBAppearance)dbImporterManager.getDBImporter(DBImporterEnum.APPEARANCE);
					if (dbAppearance != null)
						id = dbAppearance.insert(appearance, CityGMLClass.CITYMODEL, 0);

				} else if (work.getCityGMLClass().childOrSelf(CityGMLClass.CITYOBJECT)){
					CityObject cityObject = (CityObject)work;

					// gml:id filter
					if (featureGmlIdFilter.isActive()) {
						if (cityObject.getId() != null) {
							if (featureGmlIdFilter.filter(cityObject.getId()))
								return;
						} else
							return;
					}

					// gml:name filter
					if (featureGmlNameFilter.isActive()) {
						if (cityObject.getName() != null) {
							boolean success = false;

							for (Code code : cityObject.getName()) {
								if (code.getValue() != null && !featureGmlNameFilter.filter(code.getValue())) {
									success = true;
									break;
								}
							}

							if (!success)
								return;

						} else
							return;
					}

					// bounding box filter
					// first of all compute bounding box for cityobject since we need it anyways
					if (cityObject.getBoundedBy() == null)
						cityObject.calcBoundedBy();
					else
						// re-work on this
						cityObject.getBoundedBy().convertEnvelope();

					// filter
					if (cityObject.getBoundedBy() != null && 
							featureBoundingBoxFilter.filter(cityObject.getBoundedBy().getEnvelope()))
						return;

					// top-level filter
					if (featureClassFilter.filter(work.getCityGMLClass()))
						return;

					// if the cityobject did pass all filters, let us furhter work on it
					switch (work.getCityGMLClass()) {
					case BUILDING:
						Building building = (Building)work;

						DBBuilding dbBuilding = (DBBuilding)dbImporterManager.getDBImporter(DBImporterEnum.BUILDING);
						if (dbBuilding != null)
							id = dbBuilding.insert(building);

						break;
					case CITYFURNITURE:
						CityFurniture cityFurniture = (CityFurniture)work;

						DBCityFurniture dbCityFurniture = (DBCityFurniture)dbImporterManager.getDBImporter(DBImporterEnum.CITY_FURNITURE);
						if (cityFurniture != null)
							id = dbCityFurniture.insert(cityFurniture);

						break;
					case LANDUSE:
						LandUse landUse = (LandUse)work;

						DBLandUse dbLandUse = (DBLandUse)dbImporterManager.getDBImporter(DBImporterEnum.LAND_USE);
						if (dbLandUse != null)
							id = dbLandUse.insert(landUse);

						break;
					case WATERBODY:
						WaterBody waterBody = (WaterBody)work;

						DBWaterBody dbWaterBody = (DBWaterBody)dbImporterManager.getDBImporter(DBImporterEnum.WATERBODY);
						if (dbWaterBody != null)
							id = dbWaterBody.insert(waterBody);

						break;
					case PLANTCOVER:
						PlantCover plantCover = (PlantCover)work;

						DBPlantCover dbPlantCover = (DBPlantCover)dbImporterManager.getDBImporter(DBImporterEnum.PLANT_COVER);
						if (dbPlantCover != null)
							id = dbPlantCover.insert(plantCover);

						break;
					case SOLITARYVEGETATIONOBJECT:
						SolitaryVegetationObject solVegObject = (SolitaryVegetationObject)work;

						DBSolitaryVegetatObject dbSolVegObject = (DBSolitaryVegetatObject)dbImporterManager.getDBImporter(DBImporterEnum.SOLITARY_VEGETAT_OBJECT);
						if (dbSolVegObject != null)
							id = dbSolVegObject.insert(solVegObject);

						break;
					case TRANSPORTATIONCOMPLEX:
					case ROAD:
					case RAILWAY:
					case TRACK:
					case SQUARE:
						TransportationComplex transComplex = (TransportationComplex)work;

						DBTransportationComplex dbTransComplex = (DBTransportationComplex)dbImporterManager.getDBImporter(DBImporterEnum.TRANSPORTATION_COMPLEX);
						if (dbTransComplex != null)
							id = dbTransComplex.insert(transComplex);

						break;
					case RELIEFFEATURE:
						ReliefFeature reliefFeature = (ReliefFeature)work;

						DBReliefFeature dbReliefFeature = (DBReliefFeature)dbImporterManager.getDBImporter(DBImporterEnum.RELIEF_FEATURE);
						if (dbReliefFeature != null)
							id = dbReliefFeature.insert(reliefFeature);

						break;
					case GENERICCITYOBJECT:
						GenericCityObject genericCityObject = (GenericCityObject)work;

						DBGenericCityObject dbGenericCityObject = (DBGenericCityObject)dbImporterManager.getDBImporter(DBImporterEnum.GENERIC_CITYOBJECT);
						if (dbGenericCityObject != null)
							id = dbGenericCityObject.insert(genericCityObject);

						break;
					case CITYOBJECTGROUP:
						CityObjectGroup cityObjectGroup = (CityObjectGroup)work;

						DBCityObjectGroup dbCityObjectGroup = (DBCityObjectGroup)dbImporterManager.getDBImporter(DBImporterEnum.CITYOBJECTGROUP);
						if (dbCityObjectGroup != null)
							id = dbCityObjectGroup.insert(cityObjectGroup);

						break;
					}
				}

				if (id != 0)
					updateCounter++;

			} catch (SQLException sqlEx) {
				AbstractFeature feature = (AbstractFeature)work;

				LogMessageEvent log = new LogMessageEvent(
						"SQL-Fehler bei gml:id '" + feature.getId() + "': " + sqlEx,
						LogMessageEnum.ERROR);
				eventDispatcher.triggerEvent(log);
				return;
			}

			try {
				if (updateCounter == commitAfter) {
					dbImporterManager.executeBatch();
					batchConn.commit();

					eventDispatcher.triggerEvent(new TopLevelFeatureCounterEvent(updateCounter));
					updateCounter = 0;
				}
			} catch (SQLException sqlEx) {
				// uh, batch update did not work. this is serious...
				LogMessageEvent log = new LogMessageEvent(
						"SQL-Fehler: " + sqlEx,
						LogMessageEnum.ERROR);
				eventDispatcher.triggerEvent(log);
				return;
			}

		} finally {
			runLock.unlock();
		}
	}
}