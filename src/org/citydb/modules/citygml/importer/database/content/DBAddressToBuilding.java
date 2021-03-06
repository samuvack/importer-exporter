/*
 * 3D City Database - The Open Source CityGML Database
 * http://www.3dcitydb.org/
 * 
 * Copyright 2013 - 2016
 * Chair of Geoinformatics
 * Technical University of Munich, Germany
 * https://www.gis.bgu.tum.de/
 * 
 * The 3D City Database is jointly developed with the following
 * cooperation partners:
 * 
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * M.O.S.S. Computer Grafik Systeme GmbH, Taufkirchen <http://www.moss.de/>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.citydb.modules.citygml.importer.database.content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class DBAddressToBuilding implements DBImporter {
	private final Connection batchConn;
	private final DBImporterManager dbImporterManager;

	private PreparedStatement psAddressToBuilding;
	private int batchCounter;
	
	public DBAddressToBuilding(Connection batchConn, DBImporterManager dbImporterManager) throws SQLException {
		this.batchConn = batchConn;
		this.dbImporterManager = dbImporterManager;

		init();
	}

	private void init() throws SQLException {
		psAddressToBuilding = batchConn.prepareStatement("insert into ADDRESS_TO_BUILDING (BUILDING_ID, ADDRESS_ID) values (?, ?)");
	}
	
	public void insert(long addressId, long buildingId) throws SQLException {
		psAddressToBuilding.setLong(1, buildingId);
		psAddressToBuilding.setLong(2, addressId);

		psAddressToBuilding.addBatch();
		if (++batchCounter == dbImporterManager.getDatabaseAdapter().getMaxBatchSize())
			dbImporterManager.executeBatch(DBImporterEnum.ADDRESS_TO_BUILDING);
	}
	
	@Override
	public void executeBatch() throws SQLException {
		psAddressToBuilding.executeBatch();
		batchCounter = 0;
	}

	@Override
	public void close() throws SQLException {
		psAddressToBuilding.close();
	}

	@Override
	public DBImporterEnum getDBImporterType() {
		return DBImporterEnum.ADDRESS_TO_BUILDING;
	}

}
