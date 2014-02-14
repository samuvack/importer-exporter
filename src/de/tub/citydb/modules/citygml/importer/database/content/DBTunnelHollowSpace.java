/*
 * This file is part of the 3D City Database Importer/Exporter.
 * Copyright (c) 2007 - 2013
 * Institute for Geodesy and Geoinformation Science
 * Technische Universitaet Berlin, Germany
 * http://www.gis.tu-berlin.de/
 * 
 * The 3D City Database Importer/Exporter program is free software:
 * you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program. If not, see 
 * <http://www.gnu.org/licenses/>.
 * 
 * The development of the 3D City Database Importer/Exporter has 
 * been financially supported by the following cooperation partners:
 * 
 * Business Location Center, Berlin <http://www.businesslocationcenter.de/>
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * Berlin Senate of Business, Technology and Women <http://www.berlin.de/sen/wtf/>
 */
package de.tub.citydb.modules.citygml.importer.database.content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import org.citygml4j.model.citygml.CityGMLClass;
import org.citygml4j.model.citygml.tunnel.AbstractBoundarySurface;
import org.citygml4j.model.citygml.tunnel.BoundarySurfaceProperty;
import org.citygml4j.model.citygml.tunnel.HollowSpace;
import org.citygml4j.model.citygml.tunnel.IntTunnelInstallation;
import org.citygml4j.model.citygml.tunnel.IntTunnelInstallationProperty;
import org.citygml4j.model.citygml.tunnel.InteriorFurnitureProperty;
import org.citygml4j.model.citygml.tunnel.TunnelFurniture;
import org.citygml4j.model.gml.geometry.aggregates.MultiSurfaceProperty;
import org.citygml4j.model.gml.geometry.primitives.SolidProperty;

import de.tub.citydb.database.TableEnum;
import de.tub.citydb.log.Logger;
import de.tub.citydb.modules.citygml.common.database.xlink.DBXlinkSurfaceGeometry;
import de.tub.citydb.util.Util;

public class DBTunnelHollowSpace implements DBImporter {
	private final Logger LOG = Logger.getInstance();

	private final Connection batchConn;
	private final DBImporterManager dbImporterManager;

	private PreparedStatement psHollowSpace;
	private DBCityObject cityObjectImporter;
	private DBSurfaceGeometry surfaceGeometryImporter;
	private DBTunnelThematicSurface thematicSurfaceImporter;
	private DBTunnelFurniture tunnelFurnitureImporter;
	private DBTunnelInstallation tunnelInstallationImporter;

	private int batchCounter;

	public DBTunnelHollowSpace(Connection batchConn, DBImporterManager dbImporterManager) throws SQLException {
		this.batchConn = batchConn;
		this.dbImporterManager = dbImporterManager;

		init();
	}

	private void init() throws SQLException {
		StringBuilder stmt = new StringBuilder()
		.append("insert into TUNNEL_HOLLOW_SPACE (ID, CLASS, CLASS_CODESPACE, FUNCTION, FUNCTION_CODESPACE, USAGE, USAGE_CODESPACE, TUNNEL_ID, ")
		.append("LOD4_MULTI_SURFACE_ID, LOD4_SOLID_ID) values ")
		.append("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		psHollowSpace = batchConn.prepareStatement(stmt.toString());

		surfaceGeometryImporter = (DBSurfaceGeometry)dbImporterManager.getDBImporter(DBImporterEnum.SURFACE_GEOMETRY);
		cityObjectImporter = (DBCityObject)dbImporterManager.getDBImporter(DBImporterEnum.CITYOBJECT);
		thematicSurfaceImporter = (DBTunnelThematicSurface)dbImporterManager.getDBImporter(DBImporterEnum.TUNNEL_THEMATIC_SURFACE);
		tunnelFurnitureImporter = (DBTunnelFurniture)dbImporterManager.getDBImporter(DBImporterEnum.TUNNEL_FURNITURE);
		tunnelInstallationImporter = (DBTunnelInstallation)dbImporterManager.getDBImporter(DBImporterEnum.TUNNEL_INSTALLATION);
	}

	public long insert(HollowSpace hollowSpace, long tunnelId) throws SQLException {
		long hollowSpaceId = dbImporterManager.getDBId(DBSequencerEnum.CITYOBJECT_ID_SEQ);
		if (hollowSpaceId == 0)
			return 0;

		// CityObject
		cityObjectImporter.insert(hollowSpace, hollowSpaceId);

		// HollowSpace
		// ID
		psHollowSpace.setLong(1, hollowSpaceId);

		// class
		if (hollowSpace.isSetClazz() && hollowSpace.getClazz().isSetValue()) {
			psHollowSpace.setString(2, hollowSpace.getClazz().getValue());
			psHollowSpace.setString(3, hollowSpace.getClazz().getCodeSpace());
		} else {
			psHollowSpace.setNull(2, Types.VARCHAR);
			psHollowSpace.setNull(3, Types.VARCHAR);
		}

		// function
		if (hollowSpace.isSetFunction()) {
			String[] function = Util.codeList2string(hollowSpace.getFunction());
			psHollowSpace.setString(4, function[0]);
			psHollowSpace.setString(5, function[1]);
		} else {
			psHollowSpace.setNull(4, Types.VARCHAR);
			psHollowSpace.setNull(5, Types.VARCHAR);
		}

		// usage
		if (hollowSpace.isSetUsage()) {
			String[] usage = Util.codeList2string(hollowSpace.getUsage());
			psHollowSpace.setString(6, usage[0]);
			psHollowSpace.setString(7, usage[1]);
		} else {
			psHollowSpace.setNull(6, Types.VARCHAR);
			psHollowSpace.setNull(7, Types.VARCHAR);
		}

		// TUNNEL_ID
		psHollowSpace.setLong(8, tunnelId);

		// Geometry
		// lod4MultiSurface
		long geometryId = 0;

		if (hollowSpace.isSetLod4MultiSurface()) {
			MultiSurfaceProperty multiSurfacePropery = hollowSpace.getLod4MultiSurface();

			if (multiSurfacePropery.isSetMultiSurface()) {
				geometryId = surfaceGeometryImporter.insert(multiSurfacePropery.getMultiSurface(), hollowSpaceId);
				multiSurfacePropery.unsetMultiSurface();
			} else {
				// xlink
				String href = multiSurfacePropery.getHref();

				if (href != null && href.length() != 0) {
					dbImporterManager.propagateXlink(new DBXlinkSurfaceGeometry(
							href, 
							hollowSpaceId, 
							TableEnum.TUNNEL_HOLLOW_SPACE, 
							"LOD4_MULTI_SURFACE_ID"));
				}
			}
		} 

		if (geometryId != 0)
			psHollowSpace.setLong(9, geometryId);
		else
			psHollowSpace.setNull(9, Types.NULL);

		// lod4Solid
		geometryId = 0;

		if (hollowSpace.isSetLod4Solid()) {
			SolidProperty solidProperty = hollowSpace.getLod4Solid();

			if (solidProperty.isSetSolid()) {
				geometryId = surfaceGeometryImporter.insert(solidProperty.getSolid(), hollowSpaceId);
				solidProperty.unsetSolid();
			} else {
				// xlink
				String href = solidProperty.getHref();

				if (href != null && href.length() != 0) {
					dbImporterManager.propagateXlink(new DBXlinkSurfaceGeometry(
							href, 
							hollowSpaceId, 
							TableEnum.TUNNEL_HOLLOW_SPACE, 
							"LOD4_SOLID_ID"));
				}
			}
		} 

		if (geometryId != 0)
			psHollowSpace.setLong(10, geometryId);
		else
			psHollowSpace.setNull(10, Types.NULL);

		psHollowSpace.addBatch();
		if (++batchCounter == dbImporterManager.getDatabaseAdapter().getMaxBatchSize())
			dbImporterManager.executeBatch(DBImporterEnum.TUNNEL_HOLLOW_SPACE);

		// BoundarySurfaces
		if (hollowSpace.isSetBoundedBySurface()) {
			for (BoundarySurfaceProperty boundarySurfaceProperty : hollowSpace.getBoundedBySurface()) {
				AbstractBoundarySurface boundarySurface = boundarySurfaceProperty.getBoundarySurface();

				if (boundarySurface != null) {
					String gmlId = boundarySurface.getId();
					long id = thematicSurfaceImporter.insert(boundarySurface, hollowSpace.getCityGMLClass(), hollowSpaceId);

					if (id == 0) {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								hollowSpace.getCityGMLClass(), 
								hollowSpace.getId()));
						msg.append(": Failed to write ");
						msg.append(Util.getFeatureSignature(
								boundarySurface.getCityGMLClass(), 
								gmlId));

						LOG.error(msg.toString());
					}

					// free memory of nested feature
					boundarySurfaceProperty.unsetBoundarySurface();
				} else {
					// xlink
					String href = boundarySurfaceProperty.getHref();

					if (href != null && href.length() != 0) {
						LOG.error("XLink reference '" + href + "' to " + CityGMLClass.ABSTRACT_TUNNEL_BOUNDARY_SURFACE + " feature is not supported.");
					}
				}
			}
		}

		// IntBuildingInstallation
		if (hollowSpace.isSetHollowSpaceInstallation()) {
			for (IntTunnelInstallationProperty intTunnelInstProperty : hollowSpace.getHollowSpaceInstallation()) {
				IntTunnelInstallation intTunnelInst = intTunnelInstProperty.getObject();

				if (intTunnelInst != null) {
					String gmlId = intTunnelInst.getId();
					long id = tunnelInstallationImporter.insert(intTunnelInst, hollowSpace.getCityGMLClass(), hollowSpaceId);

					if (id == 0) {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								hollowSpace.getCityGMLClass(), 
								hollowSpace.getId()));
						msg.append(": Failed to write ");
						msg.append(Util.getFeatureSignature(
								intTunnelInst.getCityGMLClass(), 
								gmlId));

						LOG.error(msg.toString());
					}

					// free memory of nested feature
					intTunnelInstProperty.unsetIntTunnelInstallation();
				} else {
					// xlink
					String href = intTunnelInstProperty.getHref();

					if (href != null && href.length() != 0) {
						LOG.error("XLink reference '" + href + "' to " + CityGMLClass.INT_TUNNEL_INSTALLATION + " feature is not supported.");
					}
				}
			}
		}

		// TunnelFurniture
		if (hollowSpace.isSetInteriorFurniture()) {
			for (InteriorFurnitureProperty intFurnitureProperty : hollowSpace.getInteriorFurniture()) {
				TunnelFurniture furniture = intFurnitureProperty.getObject();

				if (furniture != null) {
					String gmlId = furniture.getId();
					long id = tunnelFurnitureImporter.insert(furniture, hollowSpaceId);

					if (id == 0) {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								hollowSpace.getCityGMLClass(), 
								hollowSpace.getId()));
						msg.append(": Failed to write ");
						msg.append(Util.getFeatureSignature(
								furniture.getCityGMLClass(), 
								gmlId));

						LOG.error(msg.toString());
					}

					// free memory of nested feature
					intFurnitureProperty.unsetTunnelFurniture();
				} else {
					// xlink
					String href = intFurnitureProperty.getHref();

					if (href != null && href.length() != 0) {
						LOG.error("XLink reference '" + href + "' to " + CityGMLClass.TUNNEL_FURNITURE + " feature is not supported.");
					}
				}
			}
		}

		// insert local appearance
		cityObjectImporter.insertAppearance(hollowSpace, hollowSpaceId);

		return hollowSpaceId;
	}

	@Override
	public void executeBatch() throws SQLException {
		psHollowSpace.executeBatch();
		batchCounter = 0;
	}

	@Override
	public void close() throws SQLException {
		psHollowSpace.close();
	}

	@Override
	public DBImporterEnum getDBImporterType() {
		return DBImporterEnum.TUNNEL_HOLLOW_SPACE;
	}

}
