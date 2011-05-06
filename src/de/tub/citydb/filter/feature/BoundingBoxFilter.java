/*
 * This file is part of the 3D City Database Importer/Exporter.
 * Copyright (c) 2007 - 2011
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
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
package de.tub.citydb.filter.feature;

import java.sql.SQLException;
import java.util.List;

import org.citygml4j.geometry.BoundingVolume;
import org.citygml4j.geometry.Point;
import org.citygml4j.model.gml.DirectPosition;
import org.citygml4j.model.gml.Envelope;

import de.tub.citydb.config.Config;
import de.tub.citydb.config.project.filter.AbstractFilterConfig;
import de.tub.citydb.config.project.filter.BoundingBox;
import de.tub.citydb.config.project.filter.TiledBoundingBox;
import de.tub.citydb.config.project.filter.Tiling;
import de.tub.citydb.config.project.filter.TilingMode;
import de.tub.citydb.filter.Filter;
import de.tub.citydb.filter.FilterMode;
import de.tub.citydb.util.DBUtil;

public class BoundingBoxFilter implements Filter<Envelope> {
	private final AbstractFilterConfig filterConfig;
	private final FilterMode mode;
	private final DBUtil dbUtil;
	private final Config config;

	private boolean isActive;
	private boolean useTiling;
	private BoundingBox boundingBoxConfig;

	private BoundingVolume boundingBox;
	private BoundingVolume activeBoundingBox;

	private double rowHeight = 0;  
	private double columnWidth = 0;
	private int rows = 1;  
	private int columns = 1;
	private int activeRow = 1;
	private int activeColumn = 1;

	public BoundingBoxFilter(Config config, FilterMode mode, DBUtil dbUtil) {
		this.mode = mode;
		this.dbUtil = dbUtil;
		this.config = config;

		if (mode == FilterMode.EXPORT)
			filterConfig = config.getProject().getExporter().getFilter();
		else if (mode == FilterMode.KML_EXPORT)
			filterConfig = config.getProject().getKmlExporter().getFilter();
		else
			filterConfig = config.getProject().getImporter().getFilter();			

		init();
	}

	private void init() {
		isActive = filterConfig.isSetComplexFilter() &&
		filterConfig.getComplexFilter().getBoundingBox().isSet();

		if (isActive) {
			boundingBoxConfig = filterConfig.getComplexFilter().getBoundingBox();
			if (mode == FilterMode.EXPORT || mode == FilterMode.KML_EXPORT)
				useTiling = ((TiledBoundingBox)boundingBoxConfig).getTiling().getMode() != TilingMode.NO_TILING;

			Double minX = boundingBoxConfig.getLowerLeftCorner().getX();
			Double minY = boundingBoxConfig.getLowerLeftCorner().getY();
			Double maxX = boundingBoxConfig.getUpperRightCorner().getX();
			Double maxY = boundingBoxConfig.getUpperRightCorner().getY();

			if (minX != null && minY != null && maxX != null && maxY != null) {
				boundingBox = new BoundingVolume(
						new Point(minX, minY, 0),
						new Point(maxX, maxY, 0)
				);

				// check whether we have to transform coordinate values
				int dbSrid = config.getInternal().getOpenConnection().getMetaData().getSrid();
				int bboxSrid = boundingBoxConfig.getSRS().getSrid();

				if (boundingBoxConfig.getSRS().isSupported() && bboxSrid != dbSrid) {			
					try {
						boundingBox = dbUtil.transformBBox(boundingBox, bboxSrid, dbSrid);
					} catch (SQLException sqlEx) {
						//
					}
				}

				activeBoundingBox = boundingBox;

				if (useTiling) {
					Tiling tiling = ((TiledBoundingBox)boundingBoxConfig).getTiling();					
					rows = tiling.getRows();
					columns = tiling.getColumns();
					rowHeight = (boundingBox.getUpperCorner().getY() - boundingBox.getLowerCorner().getY()) / rows;  
					columnWidth = (boundingBox.getUpperCorner().getX() - boundingBox.getLowerCorner().getX()) / columns;
				}
			} 
			else
				isActive = false;
		}
	}

	@Override
	public boolean isActive() {
		return isActive;
	}

	public void reset() {
		init();
	}

	public boolean filter(Envelope envelope) {
		if (isActive) {
			if (!envelope.isSetLowerCorner() || !envelope.isSetUpperCorner())
				return true;

			DirectPosition lowerCorner = envelope.getLowerCorner();
			DirectPosition upperCorner = envelope.getUpperCorner();

			if (!lowerCorner.isSetValue() || !upperCorner.isSetValue())
				return true;

			List<Double> lowerCornerValue = lowerCorner.getValue();
			List<Double> upperCornerValue = upperCorner.getValue();

			if (lowerCornerValue.size() < 2 || upperCornerValue.size() < 2)
				return true;

			Double minX = lowerCornerValue.get(0);
			Double minY = lowerCornerValue.get(1);

			Double maxX = upperCornerValue.get(0);
			Double maxY = upperCornerValue.get(1);

			if (!useTiling) { // no tiling
				if (boundingBoxConfig.isSetContainMode()) {
					if (minX >= activeBoundingBox.getLowerCorner().getX() &&
							minY >= activeBoundingBox.getLowerCorner().getY() &&
							maxX <= activeBoundingBox.getUpperCorner().getX() &&
							maxY <= activeBoundingBox.getUpperCorner().getY())
						return false;
					else
						return true;
				}

				else if (boundingBoxConfig.isSetOverlapMode()) {
					if (minX >= activeBoundingBox.getUpperCorner().getX() ||
							maxX <= activeBoundingBox.getLowerCorner().getX() ||
							minY >= activeBoundingBox.getUpperCorner().getY() ||
							maxY <= activeBoundingBox.getLowerCorner().getY())
						return true;
					else 
						return false;
				}
			}
			else { // manual tiling
				double centroidX = (minX + maxX) / 2;
				double centroidY = (minY + maxY) / 2;
				if (centroidX >= activeBoundingBox.getLowerCorner().getX() &&
						centroidY > activeBoundingBox.getLowerCorner().getY() &&
						centroidX < activeBoundingBox.getUpperCorner().getX() &&
						centroidY <= activeBoundingBox.getUpperCorner().getY())
					return false;
				else
					return true;
			}
		}

		return false;
	}

	public BoundingVolume getFilterState() {
		return activeBoundingBox;
	}

	public void setActiveTile(int activeRow, int activeColumn) {
		if (!useTiling || 
				activeRow < 0 || activeRow > rows ||
				activeColumn < 0 || activeColumn > columns)
			return;

		this.activeRow = activeRow;
		this.activeColumn = activeColumn;

		double lowerLeftX = boundingBox.getLowerCorner().getX() + (activeColumn * columnWidth);
		double lowerLeftY = boundingBox.getLowerCorner().getY() + (activeRow * rowHeight);
		double upperRightX = lowerLeftX + columnWidth;
		double upperRightY = lowerLeftY + rowHeight;

		activeBoundingBox = new BoundingVolume(
				new Point(lowerLeftX, lowerLeftY, 0),
				new Point(upperRightX, upperRightY, 0)
		);
	}

	public int getTileRow() {
		return activeRow;
	}

	public int getTileColumn() {
		return activeColumn;
	}
}