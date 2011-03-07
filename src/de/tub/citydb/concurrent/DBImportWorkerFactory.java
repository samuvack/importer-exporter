package de.tub.citydb.concurrent;

import java.sql.SQLException;

import de.tub.citydb.config.Config;
import de.tub.citydb.db.DBConnectionPool;
import de.tub.citydb.db.cache.DBGmlIdLookupServerManager;
import de.tub.citydb.db.xlink.DBXlink;
import de.tub.citydb.event.EventDispatcher;
import de.tub.citydb.filter.ImportFilter;
import de.tub.citygml4j.CityGMLFactory;
import de.tub.citygml4j.model.citygml.core.CityGMLBase;

public class DBImportWorkerFactory implements WorkerFactory<CityGMLBase> {
	private final DBConnectionPool dbConnectionPool;
	private final WorkerPool<DBXlink> xlinkWorkerPool;
	private final DBGmlIdLookupServerManager lookupServerManager;
	private final CityGMLFactory cityGMLFactory;
	private final ImportFilter importFilter;
	private final Config config;
	private final EventDispatcher eventDispatcher;

	public DBImportWorkerFactory(DBConnectionPool dbConnectionPool,
			WorkerPool<DBXlink> xlinkWorkerPool,
			DBGmlIdLookupServerManager lookupServerManager,
			CityGMLFactory cityGMLFactory,
			ImportFilter importFilter,
			Config config,
			EventDispatcher eventDispatcher) {
		this.dbConnectionPool = dbConnectionPool;
		this.xlinkWorkerPool = xlinkWorkerPool;
		this.lookupServerManager = lookupServerManager;
		this.cityGMLFactory = cityGMLFactory;
		this.importFilter = importFilter;
		this.config = config;
		this.eventDispatcher = eventDispatcher;
	}

	@Override
	public Worker<CityGMLBase> getWorker() {
		DBImportWorker dbWorker = null;

		try {
			dbWorker = new DBImportWorker(dbConnectionPool, 
					xlinkWorkerPool, 
					lookupServerManager,
					cityGMLFactory,
					importFilter,
					config, 
					eventDispatcher);
		} catch (SQLException sqlEx) {
			// could not instantiate DBWorker
		}

		return dbWorker;
	}
}
