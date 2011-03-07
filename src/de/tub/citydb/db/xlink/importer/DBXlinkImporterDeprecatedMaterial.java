package de.tub.citydb.db.xlink.importer;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import de.tub.citydb.db.temp.DBTempGTT;
import de.tub.citydb.db.xlink.DBXlinkDeprecatedMaterial;

public class DBXlinkImporterDeprecatedMaterial implements DBXlinkImporter {
	private final DBTempGTT tempTable;
	private final DBXlinkImporterManager dbXlinkImporterManager;

	private PreparedStatement psXlink;

	public DBXlinkImporterDeprecatedMaterial(DBTempGTT tempTable, DBXlinkImporterManager dbXlinkImporterManager) throws SQLException {
		this.tempTable = tempTable;
		this.dbXlinkImporterManager = dbXlinkImporterManager;

		init();
	}

	private void init() throws SQLException {
		psXlink = tempTable.getWriter().prepareStatement("insert into " + tempTable.getTableName() + 
			" (ID, GMLID, SURFACE_GEOMETRY_ID) values " +
			"(?, ?, ?)");
	}

	public boolean insert(DBXlinkDeprecatedMaterial xlinkEntry) throws SQLException {
		psXlink.setLong(1, xlinkEntry.getId());
		psXlink.setString(2, xlinkEntry.getGmlId());
		psXlink.setLong(3, xlinkEntry.getSurfaceGeometryId());

		psXlink.addBatch();

		return true;
	}

	@Override
	public void executeBatch() throws SQLException {
		psXlink.executeBatch();
	}

	@Override
	public DBXlinkImporterEnum getDBXlinkImporterType() {
		return DBXlinkImporterEnum.XLINK_DEPRECATED_MATERIAL;
	}

}
