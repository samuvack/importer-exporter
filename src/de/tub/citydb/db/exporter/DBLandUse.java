package de.tub.citydb.db.exporter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBException;

import de.tub.citydb.config.Config;
import de.tub.citydb.filter.ExportFilter;
import de.tub.citydb.filter.feature.FeatureClassFilter;
import de.tub.citydb.util.Util;
import de.tub.citygml4j.CityGMLFactory;
import de.tub.citygml4j.implementation.gml._3_1_1.MultiSurfacePropertyImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.StringOrRefImpl;
import de.tub.citygml4j.model.citygml.CityGMLClass;
import de.tub.citygml4j.model.citygml.landuse.LandUse;
import de.tub.citygml4j.model.citygml.landuse.LandUseModule;
import de.tub.citygml4j.model.gml.GMLClass;
import de.tub.citygml4j.model.gml.MultiSurface;
import de.tub.citygml4j.model.gml.MultiSurfaceProperty;
import de.tub.citygml4j.model.gml.StringOrRef;

public class DBLandUse implements DBExporter {
	private final DBExporterManager dbExporterManager;
	private final CityGMLFactory cityGMLFactory;
	private final Config config;
	private final Connection connection;

	private PreparedStatement psLandUse;
	private ResultSet rs;

	private DBSurfaceGeometry surfaceGeometryExporter;
	private DBCityObject cityObjectExporter;
	private FeatureClassFilter featureClassFilter;

	private String gmlNameDelimiter;
	private LandUseModule luseFactory;

	public DBLandUse(Connection connection, CityGMLFactory cityGMLFactory, ExportFilter exportFilter, Config config, DBExporterManager dbExporterManager) throws SQLException {
		this.connection = connection;
		this.cityGMLFactory = cityGMLFactory;
		this.config = config;
		this.dbExporterManager = dbExporterManager;
		this.featureClassFilter = exportFilter.getFeatureClassFilter();

		init();
	}

	private void init() throws SQLException {
		gmlNameDelimiter = config.getInternal().getGmlNameDelimiter();
		luseFactory = config.getProject().getExporter().getModuleVersion().getLandUse().getModule();

		psLandUse = connection.prepareStatement("select * from LAND_USE where ID = ?");

		surfaceGeometryExporter = (DBSurfaceGeometry)dbExporterManager.getDBExporter(DBExporterEnum.SURFACE_GEOMETRY);
		cityObjectExporter = (DBCityObject)dbExporterManager.getDBExporter(DBExporterEnum.CITYOBJECT);
	}

	public boolean read(DBSplittingResult splitter) throws SQLException, JAXBException {
		LandUse landUse = cityGMLFactory.createLandUse(luseFactory);
		long landUseId = splitter.getPrimaryKey();

		// cityObject stuff
		boolean success = cityObjectExporter.read(landUse, landUseId, true);
		if (!success)
			return false;

		psLandUse.setLong(1, landUseId);
		rs = psLandUse.executeQuery();

		if (rs.next()) {
			String gmlName = rs.getString("NAME");
			String gmlNameCodespace = rs.getString("NAME_CODESPACE");

			Util.dbGmlName2featureName(landUse, gmlName, gmlNameCodespace, gmlNameDelimiter);

			String description = rs.getString("DESCRIPTION");
			if (description != null) {
				StringOrRef stringOrRef = new StringOrRefImpl();
				stringOrRef.setValue(description);
				landUse.setDescription(stringOrRef);
			}

			String clazz = rs.getString("CLASS");
			if (clazz != null) {
				landUse.setClazz(clazz);
			}

			String function = rs.getString("FUNCTION");
			if (function != null) {
				Pattern p = Pattern.compile("\\s+");
				String[] functionList = p.split(function.trim());
				landUse.setFunction(Arrays.asList(functionList));
			}

			String usage = rs.getString("USAGE");
			if (usage != null) {
				Pattern p = Pattern.compile("\\s+");
				String[] usageList = p.split(usage.trim());
				landUse.setUsage(Arrays.asList(usageList));
			}

			for (int lod = 0; lod < 5 ; lod++) {
				long multiSurfaceId = rs.getLong("LOD" + lod + "_MULTI_SURFACE_ID");

				if (!rs.wasNull() && multiSurfaceId != 0) {
					DBSurfaceGeometryResult geometry = surfaceGeometryExporter.read(multiSurfaceId);

					if (geometry != null && geometry.getType() == GMLClass.MULTISURFACE) {
						MultiSurfaceProperty multiSurfaceProperty = new MultiSurfacePropertyImpl();

						if (geometry.getAbstractGeometry() != null)
							multiSurfaceProperty.setMultiSurface((MultiSurface)geometry.getAbstractGeometry());
						else
							multiSurfaceProperty.setHref(geometry.getTarget());

						switch (lod) {
						case 0:
							landUse.setLod0MultiSurface(multiSurfaceProperty);
							break;
						case 1:
							landUse.setLod1MultiSurface(multiSurfaceProperty);
							break;
						case 2:
							landUse.setLod2MultiSurface(multiSurfaceProperty);
							break;
						case 3:
							landUse.setLod3MultiSurface(multiSurfaceProperty);
							break;
						case 4:
							landUse.setLod4MultiSurface(multiSurfaceProperty);
							break;
						}
					}
				}
			}
		}

		if (landUse.getId() != null && !featureClassFilter.filter(CityGMLClass.CITYOBJECTGROUP))
			dbExporterManager.putGmlId(landUse.getId(), landUseId, landUse.getCityGMLClass());
		dbExporterManager.print(landUse);
		return true;
	}

	@Override
	public DBExporterEnum getDBExporterType() {
		return DBExporterEnum.LAND_USE;
	}

}
