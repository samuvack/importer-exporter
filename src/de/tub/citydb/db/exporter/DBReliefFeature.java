package de.tub.citydb.db.exporter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.xml.bind.JAXBException;

import oracle.spatial.geometry.JGeometry;
import oracle.sql.STRUCT;
import de.tub.citydb.config.Config;
import de.tub.citydb.filter.ExportFilter;
import de.tub.citydb.filter.feature.FeatureClassFilter;
import de.tub.citydb.util.Util;
import de.tub.citygml4j.CityGMLFactory;
import de.tub.citygml4j.implementation.gml._3_1_1.LengthImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.StringOrRefImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.TinImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.TrianglePatchArrayPropertyImpl;
import de.tub.citygml4j.model.citygml.CityGMLClass;
import de.tub.citygml4j.model.citygml.relief.BreaklineRelief;
import de.tub.citygml4j.model.citygml.relief.MassPointRelief;
import de.tub.citygml4j.model.citygml.relief.ReliefComponent;
import de.tub.citygml4j.model.citygml.relief.ReliefComponentProperty;
import de.tub.citygml4j.model.citygml.relief.ReliefFeature;
import de.tub.citygml4j.model.citygml.relief.ReliefModule;
import de.tub.citygml4j.model.citygml.relief.TINRelief;
import de.tub.citygml4j.model.citygml.relief.TinProperty;
import de.tub.citygml4j.model.gml.ControlPoint;
import de.tub.citygml4j.model.gml.GMLClass;
import de.tub.citygml4j.model.gml.Length;
import de.tub.citygml4j.model.gml.LineStringSegmentArrayProperty;
import de.tub.citygml4j.model.gml.MultiCurveProperty;
import de.tub.citygml4j.model.gml.MultiPointProperty;
import de.tub.citygml4j.model.gml.PolygonProperty;
import de.tub.citygml4j.model.gml.StringOrRef;
import de.tub.citygml4j.model.gml.Tin;
import de.tub.citygml4j.model.gml.TriangulatedSurface;

public class DBReliefFeature implements DBExporter {
	private final DBExporterManager dbExporterManager;
	private final CityGMLFactory cityGMLFactory;
	private final Config config;
	private final Connection connection;

	private PreparedStatement psReliefFeature;
	private ResultSet rs;

	private DBSurfaceGeometry surfaceGeometryExporter;
	private DBCityObject cityObjectExporter;
	private DBSdoGeometry sdoGeometry;
	private FeatureClassFilter featureClassFilter;

	private String gmlNameDelimiter;
	private ReliefModule demFactory;

	public DBReliefFeature(Connection connection, CityGMLFactory cityGMLFactory, ExportFilter exportFilter, Config config, DBExporterManager dbExporterManager) throws SQLException {
		this.connection = connection;
		this.cityGMLFactory = cityGMLFactory;
		this.config = config;
		this.dbExporterManager = dbExporterManager;
		this.featureClassFilter = exportFilter.getFeatureClassFilter();

		init();
	}

	private void init() throws SQLException {
		gmlNameDelimiter = config.getInternal().getGmlNameDelimiter();
		demFactory = config.getProject().getExporter().getModuleVersion().getRelief().getModule();

		psReliefFeature = connection.prepareStatement("select rf.ID as RF_ID, rf.NAME as RF_NAME, rf.NAME_CODESPACE as RF_NAME_CODESPACE, rf.DESCRIPTION as RF_DESCRIPTION, rf.LOD as RF_LOD, " +
				"rc.ID as RC_ID, rc.NAME as RC_NAME, rc.NAME_CODESPACE as RC_NAME_CODESPACE, rc.DESCRIPTION as RC_DESCRIPTION, rc.LOD as RC_LOD, rc.EXTENT as RC_EXTENT, " +
				"tr.ID as TR_ID, tr.MAX_LENGTH as TR_MAX_LENGTH, tr.STOP_LINES as TR_STOP_LINES, tr.BREAK_LINES as TR_BREAK_LINES, tr.CONTROL_POINTS as TR_CONTROL_POINTS, tr.SURFACE_GEOMETRY_ID as TR_SURFACE_GEOMETRY_ID, " +
				"mr.ID as MR_ID, mr.RELIEF_POINTS as MR_RELIEF_POINTS, " +
				"br.ID as BR_ID, br.RIDGE_OR_VALLEY_LINES as BR_RIDGE_OR_VALLEY_LINES, br.BREAK_LINES as BR_BREAK_LINES " +
				"from RELIEF_FEATURE rf inner join RELIEF_FEAT_TO_REL_COMP rf2rc on rf2rc.RELIEF_FEATURE_ID=rf.ID inner join RELIEF_COMPONENT rc on rf2rc.RELIEF_COMPONENT_ID=rc.ID " +
				"left join TIN_RELIEF tr on tr.ID=rc.ID " +
				"left join MASSPOINT_RELIEF mr on mr.ID=rc.ID " +
				"left join BREAKLINE_RELIEF br on br.ID=rc.ID where rf.ID=?");

		surfaceGeometryExporter = (DBSurfaceGeometry)dbExporterManager.getDBExporter(DBExporterEnum.SURFACE_GEOMETRY);
		cityObjectExporter = (DBCityObject)dbExporterManager.getDBExporter(DBExporterEnum.CITYOBJECT);
		sdoGeometry = (DBSdoGeometry)dbExporterManager.getDBExporter(DBExporterEnum.SDO_GEOMETRY);
	}

	public boolean read(DBSplittingResult splitter) throws SQLException, JAXBException {
		ReliefFeature reliefFeature = cityGMLFactory.createReliefFeature(demFactory);
		ReliefComponent reliefComponent = null;
		long reliefFeatureId = splitter.getPrimaryKey();
		
		// cityObject stuff
		boolean success = cityObjectExporter.read(reliefFeature, reliefFeatureId, true);
		if (!success)
			return false;

		psReliefFeature.setLong(1, reliefFeatureId);
		rs = psReliefFeature.executeQuery();

		boolean isInited = false;

		while (rs.next()) {
			if (!isInited) {
				// reliefFeature object
				// just handle once
				String gmlName = rs.getString("RF_NAME");
				String gmlNameCodespace = rs.getString("RF_NAME_CODESPACE");

				Util.dbGmlName2featureName(reliefFeature, gmlName, gmlNameCodespace, gmlNameDelimiter);

				String description = rs.getString("RF_DESCRIPTION");
				if (description != null) {
					StringOrRef stringOrRef = new StringOrRefImpl();
					stringOrRef.setValue(description);
					reliefFeature.setDescription(stringOrRef);
				}

				int lod = rs.getInt("RF_LOD");
				if (rs.wasNull())
					reliefFeature.setLod(0);
				else
					reliefFeature.setLod(lod);

				isInited = true;
			}

			// get reliefComponents content
			long reliefComponentId = rs.getLong("RC_ID");
			if (rs.wasNull())
				continue;

			reliefComponent = null;
			long tinReliefId = rs.getLong("TR_ID");
			long massPointReliefId = rs.getLong("MR_ID");
			long breaklineReliedId = rs.getLong("BR_ID");

			if (tinReliefId != 0)
				reliefComponent = cityGMLFactory.createTINRelief(demFactory);
			else if (massPointReliefId != 0)
				reliefComponent = cityGMLFactory.createMassPointRelief(demFactory);
			else if (breaklineReliedId != 0)
				reliefComponent = cityGMLFactory.createBreaklineRelief(demFactory);

			if (reliefComponent == null)
				continue;

			// cityobject stuff
			cityObjectExporter.read(reliefComponent, reliefComponentId, false);

			if (reliefComponent.getId() != null) {
				// set xlink
				if (dbExporterManager.lookupAndPutGmlId(reliefComponent.getId(), reliefComponentId, CityGMLClass.RELIEFCOMPONENT)) {
					ReliefComponentProperty property = cityGMLFactory.createReliefComponentProperty(demFactory);
					property.setHref("#" + reliefComponent.getId());

					reliefFeature.addReliefComponent(property);
					continue;
				}
			}

			// get common data for all kinds of relief components
			String gmlName = rs.getString("RC_NAME");
			String gmlNameCodespace = rs.getString("RC_NAME_CODESPACE");

			Util.dbGmlName2featureName(reliefComponent, gmlName, gmlNameCodespace, gmlNameDelimiter);

			String description = rs.getString("RC_DESCRIPTION");
			if (description != null) {
				StringOrRef stringOrRef = new StringOrRefImpl();
				stringOrRef.setValue(description);
				reliefComponent.setDescription(stringOrRef);
			}

			int lod = rs.getInt("RC_LOD");
			if (rs.wasNull())
				reliefComponent.setLod(0);
			else
				reliefComponent.setLod(lod);

			JGeometry extent = null;
			STRUCT extentObj = (STRUCT)rs.getObject("RC_EXTENT");
			if (!rs.wasNull() && extentObj != null) {
				extent = JGeometry.load(extentObj);

				PolygonProperty polygonProperty = sdoGeometry.getPolygon(extent, false);
				if (polygonProperty != null)
					reliefComponent.setExtent(polygonProperty);
			}
					
			// ok, further content must be retrieved according to the
			// subtype of reliefComponent
			if (reliefComponent.getCityGMLClass() == CityGMLClass.TINRELIEF) {
				TINRelief tinRelief = (TINRelief)reliefComponent;
				
				// get TINRelief content
				Double maxLength = rs.getDouble("TR_MAX_LENGTH");
				if (rs.wasNull())
					maxLength = null;
				
				JGeometry stopLines, breakLines, controlPoints;
				stopLines = breakLines = controlPoints = null;
				
				STRUCT stopLinesObj = (STRUCT)rs.getObject("TR_STOP_LINES");
				if (!rs.wasNull() && stopLinesObj != null)
					stopLines = JGeometry.load(stopLinesObj);
				
				STRUCT breakLinesObj = (STRUCT)rs.getObject("TR_BREAK_LINES");
				if (!rs.wasNull() && breakLinesObj != null)
					breakLines = JGeometry.load(breakLinesObj);
				
				STRUCT controlPointsObj = (STRUCT)rs.getObject("TR_CONTROL_POINTS");
				if (!rs.wasNull() && controlPointsObj != null)
					controlPoints = JGeometry.load(controlPointsObj);
				
				long surfaceGeometryId = rs.getLong("TR_SURFACE_GEOMETRY_ID");
				
				// check for invalid content
				if (maxLength == null && stopLines == null && breakLines == null && controlPoints == null && surfaceGeometryId == 0)
					continue;
				
				// check whether we deal with a gml:TrinagulatedSurface or a gml:Tin
				boolean isTin = false;
				if (maxLength != null || stopLines != null || breakLines != null || controlPoints != null)
					isTin = true;
				
				// get triangle patches
				TinProperty tinProperty = cityGMLFactory.createTinProperty(demFactory);
				TriangulatedSurface triangulatedSurface = null;
				if (surfaceGeometryId != 0) {
					DBSurfaceGeometryResult geometry = surfaceGeometryExporter.read(surfaceGeometryId);
					
					// check for null until we have implemented rectifiedgridcoverage
					if (geometry == null)
						return false;
					
					// we do not allow xlinks here
					if (geometry.getType() == GMLClass.TRIANGULATEDSURFACE && geometry.getAbstractGeometry() != null) 
						triangulatedSurface = (TriangulatedSurface)geometry.getAbstractGeometry();					
				}
				
				// check for invalid gml:TriangulatedSurface
				if (!isTin && triangulatedSurface == null)
					continue;
							
				if (isTin) {
					if (triangulatedSurface != null)
						triangulatedSurface = new TinImpl(triangulatedSurface);
					else {
						triangulatedSurface = new TinImpl();
						triangulatedSurface.setTrianglePatches(new TrianglePatchArrayPropertyImpl());
					}
				}
				
				tinProperty.setObject(triangulatedSurface);
				tinRelief.setTin(tinProperty);
				
				// finally, check gml:Tin specific content
				if (isTin) {
					Tin tin = (Tin)triangulatedSurface;
					
					if (maxLength != null) {
						Length length = new LengthImpl();
						length.setValue(maxLength);
						length.setUom("urn:ogc:def:uom:UCUM::m");
						tin.setMaxLength(length);
					}
						
					if (stopLines != null) {
						List<LineStringSegmentArrayProperty> arrayPropertyList = sdoGeometry.getListOfLineStringSegmentArrayProperty(stopLines, false);
						if (arrayPropertyList != null)
							tin.setStopLines(arrayPropertyList);
					}
					
					if (breakLines != null) {
						List<LineStringSegmentArrayProperty> arrayPropertyList = sdoGeometry.getListOfLineStringSegmentArrayProperty(breakLines, false);
						if (arrayPropertyList != null)
							tin.setBreakLines(arrayPropertyList);
					}
					
					if (controlPoints != null) {
						ControlPoint controlPoint = sdoGeometry.getControlPoint(controlPoints, false);
						if (controlPoint != null)
							tin.setControlPoint(controlPoint);
					}
				}
			}
			
			else if (reliefComponent.getCityGMLClass() == CityGMLClass.MASSPOINTRELIEF) {
				MassPointRelief massPointRelief = (MassPointRelief)reliefComponent;
				
				JGeometry reliefPoints = null;				
				STRUCT reliefPointsObj = (STRUCT)rs.getObject("MR_RELIEF_POINTS");
				if (!rs.wasNull() && reliefPointsObj != null)
					reliefPoints = JGeometry.load(reliefPointsObj);
				
				if (reliefPoints != null) {
					MultiPointProperty multiPointProperty = sdoGeometry.getMultiPointProperty(reliefPoints, false);
					if (multiPointProperty != null)
						massPointRelief.setReliefPoints(multiPointProperty);
				}
			}
			
			else if (reliefComponent.getCityGMLClass() == CityGMLClass.BREAKLINERELIEF) {
				BreaklineRelief breaklineRelief = (BreaklineRelief)reliefComponent;
				
				JGeometry ridgeOrValleyLines, breakLines;
				ridgeOrValleyLines = breakLines = null;
				
				STRUCT ridgeOrValleyLinesObj = (STRUCT)rs.getObject("BR_RIDGE_OR_VALLEY_LINES");
				if (!rs.wasNull() && ridgeOrValleyLinesObj != null)
					ridgeOrValleyLines = JGeometry.load(ridgeOrValleyLinesObj);
				
				STRUCT breakLinesObj = (STRUCT)rs.getObject("BR_BREAK_LINES");
				if (!rs.wasNull() && breakLinesObj != null)
					breakLines = JGeometry.load(breakLinesObj);
				
				if (ridgeOrValleyLines != null) {
					MultiCurveProperty multiCurveProperty = sdoGeometry.getMultiCurveProperty(ridgeOrValleyLines, false);
					if (multiCurveProperty != null)					
						breaklineRelief.setRidgeOrValleyLines(multiCurveProperty);
				}
				
				if (breakLines != null) {
					MultiCurveProperty multiCurveProperty = sdoGeometry.getMultiCurveProperty(breakLines, false);
					if (multiCurveProperty != null)					
						breaklineRelief.setBreaklines(multiCurveProperty);
				}
			}
			
			else if (reliefComponent.getCityGMLClass() == CityGMLClass.RASTERRELIEF) {
				System.out.println("RasterRelief is not supported yet.");
			}
			
			// add reliefComponent to reliefFeature
			ReliefComponentProperty property = cityGMLFactory.createReliefComponentProperty(demFactory);
			property.setObject(reliefComponent);
			reliefFeature.addReliefComponent(property);
		}

		if (reliefFeature.getId() != null && !featureClassFilter.filter(CityGMLClass.CITYOBJECTGROUP))
			dbExporterManager.putGmlId(reliefFeature.getId(), reliefFeatureId, reliefFeature.getCityGMLClass());
		dbExporterManager.print(reliefFeature);
		return true;
	}

	@Override
	public DBExporterEnum getDBExporterType() {
		return DBExporterEnum.RELIEF_FEATURE;
	}

}
