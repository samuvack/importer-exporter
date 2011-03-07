package de.tub.citydb.db.exporter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;

import oracle.spatial.geometry.JGeometry;
import oracle.sql.STRUCT;
import de.tub.citydb.db.DBTableEnum;
import de.tub.citydb.filter.ExportFilter;
import de.tub.citydb.filter.feature.BoundingBoxFilter;
import de.tub.citydb.filter.feature.FeatureClassFilter;
import de.tub.citydb.filter.feature.GmlIdFilter;
import de.tub.citydb.filter.feature.GmlNameFilter;
import de.tub.citydb.util.Util;
import de.tub.citygml4j.CityGMLFactory;
import de.tub.citygml4j.geometry.Point;
import de.tub.citygml4j.implementation.gml._3_1_1.EnvelopeImpl;
import de.tub.citygml4j.model.citygml.CityGMLClass;
import de.tub.citygml4j.model.citygml.core.CityObject;
import de.tub.citygml4j.model.citygml.core.CoreModule;
import de.tub.citygml4j.model.citygml.core.GeneralizationRelation;
import de.tub.citygml4j.model.gml.Envelope;

public class DBGeneralization implements DBExporter {
	private final DBExporterManager dbExporterManager;
	private final CityGMLFactory cityGMLFactory;
	private final ExportFilter exportFilter;
	private final Connection connection;

	private PreparedStatement psGeneralization;
	private ResultSet rs;

	// filter
	private FeatureClassFilter featureClassFilter;
	private GmlIdFilter featureGmlIdFilter;
	private GmlNameFilter featureGmlNameFilter;
	private BoundingBoxFilter boundingBoxFilter;

	public DBGeneralization(Connection connection, CityGMLFactory cityGMLFactory, ExportFilter exportFilter, DBExporterManager dbExporterManager) throws SQLException {
		this.dbExporterManager = dbExporterManager;
		this.cityGMLFactory = cityGMLFactory;
		this.exportFilter = exportFilter;
		this.connection = connection;

		init();
	}

	private void init() throws SQLException {
		featureClassFilter = exportFilter.getFeatureClassFilter();
		featureGmlIdFilter = exportFilter.getGmlIdFilter();
		featureGmlNameFilter = exportFilter.getGmlNameFilter();
		boundingBoxFilter = exportFilter.getBoundingBoxFilter();

		psGeneralization = connection.prepareStatement("select GMLID, CLASS_ID, ENVELOPE from CITYOBJECT where ID=?");
	}

	public void read(CityObject cityObject, long cityObjectId, CoreModule coreFactory, HashSet<Long> generalizesToSet) throws SQLException {		
		for (Long generalizationId : generalizesToSet) {
			psGeneralization.setLong(1, generalizationId);
			rs = psGeneralization.executeQuery();

			if (rs.next()) {
				String gmlId = rs.getString("GMLID");			
				if (rs.wasNull() || gmlId == null)
					continue;

				int classId = rs.getInt("CLASS_ID");			
				CityGMLClass type = Util.classId2cityObject(classId);			
				STRUCT struct = (STRUCT)rs.getObject("ENVELOPE");

				if (!rs.wasNull() && struct != null && boundingBoxFilter.isActive()) {
					JGeometry jGeom = JGeometry.load(struct);
					Envelope env = new EnvelopeImpl();

					double[] points = jGeom.getOrdinatesArray();
					Point lower = new Point(points[0], points[1], points[2]);
					Point upper = new Point(points[3], points[4], points[5]);

					env.setLowerCorner(lower);
					env.setUpperCorner(upper);

					if (boundingBoxFilter.filter(env))
						continue;
				}	

				if (featureGmlIdFilter.isActive() && featureGmlIdFilter.filter(gmlId))
					continue;

				if (featureClassFilter.isActive() && featureClassFilter.filter(type))
					continue;

				if (featureGmlNameFilter.isActive()) {
					// we need to get the gml:name of the feature 
					// we only check top-level features
					DBTableEnum table = null;

					switch (type) {
					case BUILDING:
						table = DBTableEnum.BUILDING;
						break;
					case CITYFURNITURE:
						table = DBTableEnum.CITY_FURNITURE;
						break;
					case LANDUSE:
						table = DBTableEnum.LAND_USE;
						break;
					case WATERBODY:
						table = DBTableEnum.WATERBODY;
						break;
					case PLANTCOVER:
						table = DBTableEnum.SOLITARY_VEGETAT_OBJECT;
						break;
					case SOLITARYVEGETATIONOBJECT:
						table = DBTableEnum.PLANT_COVER;
						break;
					case TRANSPORTATIONCOMPLEX:
					case ROAD:
					case RAILWAY:
					case TRACK:
					case SQUARE:
						table = DBTableEnum.TRANSPORTATION_COMPLEX;
						break;
					case RELIEFFEATURE:
						table = DBTableEnum.RELIEF_FEATURE;
						break;
					case GENERICCITYOBJECT:
						table = DBTableEnum.GENERIC_CITYOBJECT;
						break;
					case CITYOBJECTGROUP:
						table = DBTableEnum.CITYOBJECTGROUP;
						break;
					}

					if (table != null) {
						Statement stmt = null;
						ResultSet nameRs = null;

						try {
							String query = "select NAME from " + table.toString() + " where ID=" + generalizationId;
							stmt = connection.createStatement();

							nameRs = stmt.executeQuery(query);
							if (nameRs.next()) {
								String gmlName = nameRs.getString("NAME");
								if (gmlName != null && featureGmlNameFilter.filter(gmlName))
									continue;
							}

						} catch (SQLException sqlEx) {
							continue;
						} finally {
							if (nameRs != null) {
								try {
									nameRs.close();
								} catch (SQLException sqlEx) {
									//
								}

								nameRs = null;
							}

							if (stmt != null) {
								try {
									stmt.close();
								} catch (SQLException sqlEx) {
									//
								}

								stmt = null;
							}
						}
					}
				}

				GeneralizationRelation generalizesTo = cityGMLFactory.createGeneralizationRelation(coreFactory);
				generalizesTo.setHref("#" + gmlId);
				cityObject.addGeneralizesTo(generalizesTo);
			}
		}
	}

	@Override
	public DBExporterEnum getDBExporterType() {
		return DBExporterEnum.GENERALIZATION;
	}

}
