package de.tub.citydb.db.exporter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;

import oracle.spatial.geometry.JGeometry;
import oracle.sql.STRUCT;
import de.tub.citydb.config.Config;
import de.tub.citygml4j.implementation.gml._3_1_1.CompositeSolidImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.CompositeSurfaceImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.DirectPositionListImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.ExteriorImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.InteriorImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.LinearRingImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.MultiSolidImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.MultiSurfaceImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.OrientableSurfaceImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.PolygonImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.SolidImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.SolidPropertyImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.SurfacePropertyImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.TriangleImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.TrianglePatchArrayPropertyImpl;
import de.tub.citygml4j.implementation.gml._3_1_1.TriangulatedSurfaceImpl;
import de.tub.citygml4j.model.citygml.CityGMLClass;
import de.tub.citygml4j.model.gml.AbstractGeometry;
import de.tub.citygml4j.model.gml.AbstractSolid;
import de.tub.citygml4j.model.gml.AbstractSurface;
import de.tub.citygml4j.model.gml.CompositeSolid;
import de.tub.citygml4j.model.gml.CompositeSurface;
import de.tub.citygml4j.model.gml.DirectPositionList;
import de.tub.citygml4j.model.gml.Exterior;
import de.tub.citygml4j.model.gml.GMLClass;
import de.tub.citygml4j.model.gml.Interior;
import de.tub.citygml4j.model.gml.LinearRing;
import de.tub.citygml4j.model.gml.MultiSolid;
import de.tub.citygml4j.model.gml.MultiSurface;
import de.tub.citygml4j.model.gml.OrientableSurface;
import de.tub.citygml4j.model.gml.Polygon;
import de.tub.citygml4j.model.gml.Solid;
import de.tub.citygml4j.model.gml.SolidProperty;
import de.tub.citygml4j.model.gml.SurfaceProperty;
import de.tub.citygml4j.model.gml.Triangle;
import de.tub.citygml4j.model.gml.TrianglePatchArrayProperty;
import de.tub.citygml4j.model.gml.TriangulatedSurface;

public class DBSurfaceGeometry implements DBExporter {
	private final DBExporterManager dbExporterManager;
	private final Config config;
	private final Connection connection;

	private PreparedStatement psSurfaceGeometry;
	private ResultSet rs;
	private boolean exportAppearance;

	public DBSurfaceGeometry(Connection connection, Config config, DBExporterManager dbExporterManager) throws SQLException {
		this.connection = connection;
		this.config = config;
		this.dbExporterManager = dbExporterManager;

		init();
	}

	private void init() throws SQLException {
		exportAppearance = config.getProject().getExporter().getAppearances().isSetExportAppearance();
		
		psSurfaceGeometry = connection.prepareStatement("select * from SURFACE_GEOMETRY where ROOT_ID = ?");
	}

	public DBSurfaceGeometryResult read(long rootId) throws SQLException {
		psSurfaceGeometry.setLong(1, rootId);
		rs = psSurfaceGeometry.executeQuery();
		GeometryTree geomTree = new GeometryTree();

		// firstly, read the geometry entries into a
		// flat geometry tree structure
		while (rs.next()) {
			long id = rs.getLong("ID");
			String gmlId = rs.getString("GMLID");
			long parentId = rs.getLong("PARENT_ID");
			int isSolid = rs.getInt("IS_SOLID");
			int isComposite = rs.getInt("IS_COMPOSITE");
			int isTriangulated = rs.getInt("IS_TRIANGULATED");
			Integer isXlink = rs.getInt("IS_XLINK");
			if (rs.wasNull())
				isXlink = 1;
			Integer isReverse = rs.getInt("IS_REVERSE");
			if (rs.wasNull())
				isReverse = 0;

			JGeometry geometry = null;
			STRUCT struct = (STRUCT)rs.getObject("GEOMETRY");
			if (!rs.wasNull() && struct != null)
				geometry = JGeometry.load(struct);

			// constructing a geometry node
			GeometryNode geomNode = new GeometryNode();
			geomNode.id = id;
			geomNode.gmlId = gmlId;
			geomNode.parentId = parentId;
			geomNode.isSolid = isSolid == 1;
			geomNode.isComposite = isComposite == 1;
			geomNode.isTriangulated = isTriangulated == 1;			
			geomNode.isXlink = isXlink == 1;			
			geomNode.isReverse = isReverse == 1;

			geomNode.geometry = geometry;

			// put it into our geometry tree
			geomTree.insertNode(geomNode, parentId);
		}

		// interpret geometry tree as a single abstractGeometry
		if (geomTree.root != 0)
			return rebuildGeometry(geomTree.getNode(geomTree.root), false);
		else {
			System.out.println("Could not interpret geometry structure.");
			return null;
		}
	}

	private DBSurfaceGeometryResult rebuildGeometry(GeometryNode geomNode, boolean isSetOrientableSurface) {
		// try and determine the geometry type
		GMLClass surfaceGeometryType = null;
		if (geomNode.geometry != null) {
			surfaceGeometryType = GMLClass.POLYGON;
		} else {

			if (geomNode.childNodes == null || geomNode.childNodes.size() == 0)
				return null;

			if (!geomNode.isTriangulated) {
				if (!geomNode.isSolid && geomNode.isComposite)
					surfaceGeometryType = GMLClass.COMPOSITESURFACE;
				else if (geomNode.isSolid && geomNode.isComposite)
					surfaceGeometryType = GMLClass.COMPOSITESOLID;
				else if (geomNode.isSolid && !geomNode.isComposite)
					surfaceGeometryType = GMLClass.SOLID;
				else if (!geomNode.isSolid && !geomNode.isComposite) {
					boolean isMultiSolid = true;
					for (GeometryNode childNode : geomNode.childNodes) {
						if (!childNode.isSolid){
							isMultiSolid = false;
							break;
						}
					}

					if (isMultiSolid) 
						surfaceGeometryType = GMLClass.MULTISOLID;
					else
						surfaceGeometryType = GMLClass.MULTISURFACE;
				}
			} else
				surfaceGeometryType = GMLClass.TRIANGULATEDSURFACE;
		}

		// return if we cannot identify the geometry
		if (surfaceGeometryType == null)
			return null;

		dbExporterManager.updateGeometryCounter(surfaceGeometryType);

		// check for xlinks
		if (geomNode.gmlId != null) {
			if (geomNode.isXlink) {
				if (dbExporterManager.lookupAndPutGmlId(geomNode.gmlId, geomNode.id, CityGMLClass.GMLGEOMETRY)) {
					// check whether we have to embrace the geometry with an orientableSurface
					if (geomNode.isReverse != isSetOrientableSurface) {
						OrientableSurface orientableSurface = new OrientableSurfaceImpl();				
						SurfaceProperty surfaceProperty = new SurfacePropertyImpl();
						surfaceProperty.setHref("#" + geomNode.gmlId); 
						orientableSurface.setBaseSurface(surfaceProperty);
						orientableSurface.setOrientation("-");
						dbExporterManager.updateGeometryCounter(GMLClass.ORIENTABLESURFACE);

						return new DBSurfaceGeometryResult(orientableSurface);
					} else
						return new DBSurfaceGeometryResult("#" + geomNode.gmlId, surfaceGeometryType);
				}
			} else if (exportAppearance) {
				dbExporterManager.putGmlId(geomNode.gmlId, geomNode.id, CityGMLClass.GMLGEOMETRY);
			}
		}

		// check whether we have to initialize an orientableSurface
		boolean initOrientableSurface = false;
		if (geomNode.isReverse && !isSetOrientableSurface) {
			isSetOrientableSurface = true;
			initOrientableSurface = true;
		}

		// deal with geometry according to the identified type
		// Polygon
		if (surfaceGeometryType == GMLClass.POLYGON) {
			// try and interpret JGeometry
			Polygon polygon = new PolygonImpl();
			boolean forceRingIds = false;

			if (geomNode.gmlId != null) {
				polygon.setId(geomNode.gmlId);
				forceRingIds = true;
			}

			int[] elemInfoArray = geomNode.geometry.getElemInfo();
			double[] ordinatesArray = geomNode.geometry.getOrdinatesArray();

			if (elemInfoArray.length < 3 || ordinatesArray.length == 0)
				return null;

			// we are pragmatic here. if elemInfoArray contains more than one entry,
			// we suppose we have one outer ring and anything else are inner rings.
			List<Integer> ringLimits = new ArrayList<Integer>();
			for (int i = 3; i < elemInfoArray.length; i += 3)
				ringLimits.add(elemInfoArray[i] - 1);

			ringLimits.add(ordinatesArray.length);

			// ok, rebuild surface according to this info
			boolean isExterior = elemInfoArray[1] == 1003;
			int ringElem = 0;
			int ringNo = 0;
			for (Integer ringLimit : ringLimits) {
				List<Double> values = new ArrayList<Double>();

				// check whether we have to reverse the coordinate order
				if (!geomNode.isReverse) { 
					for ( ; ringElem < ringLimit; ringElem++)
						values.add(ordinatesArray[ringElem]);
				} else {
					for (int i = ringLimit - 3; i >= ringElem; i -= 3) {
						values.add(ordinatesArray[i]);
						values.add(ordinatesArray[i + 1]);
						values.add(ordinatesArray[i + 2]);
					}
				}
					
				if (isExterior) {
					LinearRing linearRing = new LinearRingImpl();
					DirectPositionList directPositionList = new DirectPositionListImpl();

					if (forceRingIds)
						linearRing.setId(polygon.getId() + "_" + ringNo);

					directPositionList.setValue(values);
					directPositionList.setSrsDimension(3);
					linearRing.setPosList(directPositionList);

					Exterior exterior = new ExteriorImpl();
					exterior.setRing(linearRing);
					polygon.setExterior(exterior);

					isExterior = false;
					dbExporterManager.updateGeometryCounter(GMLClass.LINEARRING);
				} else {
					LinearRing linearRing = new LinearRingImpl();
					DirectPositionList directPositionList = new DirectPositionListImpl();

					if (forceRingIds)
						linearRing.setId(polygon.getId() + "_" + ringNo);

					directPositionList.setValue(values);
					directPositionList.setSrsDimension(3);
					linearRing.setPosList(directPositionList);

					Interior interior = new InteriorImpl();
					interior.setRing(linearRing);
					polygon.addInterior(interior);

					dbExporterManager.updateGeometryCounter(GMLClass.LINEARRING);
				}

				ringElem = ringLimit;
				ringNo++;
			}

			// check whether we have to embrace the polygon with an orientableSurface
			if (initOrientableSurface || (isSetOrientableSurface && !geomNode.isReverse)) {
				OrientableSurface orientableSurface = new OrientableSurfaceImpl();				
				SurfaceProperty surfaceProperty = new SurfacePropertyImpl();
				surfaceProperty.setSurface(polygon);
				orientableSurface.setBaseSurface(surfaceProperty);
				orientableSurface.setOrientation("-");
				dbExporterManager.updateGeometryCounter(GMLClass.ORIENTABLESURFACE);

				return new DBSurfaceGeometryResult(orientableSurface);
			} else
				return new DBSurfaceGeometryResult(polygon);
		}

		// compositeSurface
		else if (surfaceGeometryType == GMLClass.COMPOSITESURFACE) {
			CompositeSurface compositeSurface = new CompositeSurfaceImpl();

			if (geomNode.gmlId != null)
				compositeSurface.setId(geomNode.gmlId);

			for (GeometryNode childNode : geomNode.childNodes) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(childNode, isSetOrientableSurface);

				if (geomMember != null) {
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();
					SurfaceProperty surfaceMember = new SurfacePropertyImpl();

					if (absGeom != null) {
						switch (geomMember.getType()) {
						case POLYGON:
						case ORIENTABLESURFACE:
						case COMPOSITESURFACE:
						case TRIANGULATEDSURFACE:
							surfaceMember.setSurface((AbstractSurface)absGeom);
							break;						
						default:
							surfaceMember = null;
						}
					} else {
						surfaceMember.setHref(geomMember.getTarget());
					}

					if (surfaceMember != null)
						compositeSurface.addSurfaceMember(surfaceMember);
				}
			}

			if (compositeSurface.getSurfaceMember() != null) {
				// check whether we have to embrace the compositeSurface with an orientableSurface
				if (initOrientableSurface || (isSetOrientableSurface && !geomNode.isReverse)) {
					OrientableSurface orientableSurface = new OrientableSurfaceImpl();				
					SurfaceProperty surfaceProperty = new SurfacePropertyImpl();
					surfaceProperty.setSurface(compositeSurface);
					orientableSurface.setBaseSurface(surfaceProperty);
					orientableSurface.setOrientation("-");
					dbExporterManager.updateGeometryCounter(GMLClass.ORIENTABLESURFACE);

					return new DBSurfaceGeometryResult(orientableSurface);
				} else
					return new DBSurfaceGeometryResult(compositeSurface);
			}					

			return null;
		}

		// compositeSolid
		else if (surfaceGeometryType == GMLClass.COMPOSITESOLID) {
			CompositeSolid compositeSolid = new CompositeSolidImpl();

			if (geomNode.gmlId != null)
				compositeSolid.setId(geomNode.gmlId);

			for (GeometryNode childNode : geomNode.childNodes) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(childNode, isSetOrientableSurface);

				if (geomMember != null) {
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();
					SolidProperty solidMember = new SolidPropertyImpl();

					if (absGeom != null) {					
						switch (geomMember.getType()) {
						case SOLID:
						case COMPOSITESOLID:
							solidMember.setSolid((AbstractSolid)absGeom);
							break;
						default:
							solidMember = null;
						}
					} else {
						solidMember.setHref(geomMember.getTarget());
					}

					if (solidMember != null)
						compositeSolid.addSolidMember(solidMember);
				}
			}

			if (compositeSolid.getSolidMember() != null)
				return new DBSurfaceGeometryResult(compositeSolid);

			return null;
		}

		// a simple solid
		else if (surfaceGeometryType == GMLClass.SOLID) {
			Solid solid = new SolidImpl();

			if (geomNode.gmlId != null)
				solid.setId(geomNode.gmlId);

			// we strongly assume solids contain one single CompositeSurface
			// as exterior. Nothing else is interpreted here...
			if (geomNode.childNodes.size() == 1) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(geomNode.childNodes.firstElement(), isSetOrientableSurface);

				if (geomMember != null) {
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();
					SurfaceProperty surfaceProperty = new SurfacePropertyImpl();

					if (absGeom != null) {
						switch (geomMember.getType()) {
						case COMPOSITESURFACE:
						case ORIENTABLESURFACE:
							surfaceProperty.setSurface((AbstractSurface)absGeom);
							break;
						default:
							surfaceProperty = null;
						}
					} else {
						surfaceProperty.setHref(geomMember.getTarget());
					}

					if (surfaceProperty != null)
						solid.setExterior(surfaceProperty);
				}
			}

			if (solid.getExterior() != null)
				return new DBSurfaceGeometryResult(solid);

			return null;
		}

		// multiPolygon
		else if (surfaceGeometryType == GMLClass.MULTISOLID) {
			MultiSolid multiSolid = new MultiSolidImpl();

			if (geomNode.gmlId != null)
				multiSolid.setId(geomNode.gmlId);

			for (GeometryNode childNode : geomNode.childNodes) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(childNode, isSetOrientableSurface);

				if (geomMember != null) {
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();
					SolidProperty solidMember = new SolidPropertyImpl();

					if (absGeom != null) {
						switch (geomMember.getType()) {
						case SOLID:
						case COMPOSITESOLID:
							solidMember.setSolid((AbstractSolid)absGeom);
							break;
						default:
							solidMember = null;
						}
					} else {
						solidMember.setHref(geomMember.getTarget());
					}

					if (solidMember != null)
						multiSolid.addSolidMember(solidMember);
				}
			}

			if (multiSolid.getSolidMember() != null)
				return new DBSurfaceGeometryResult(multiSolid);

			return null;

		}

		// multiSurface
		else if (surfaceGeometryType == GMLClass.MULTISURFACE){
			MultiSurface multiSurface = new MultiSurfaceImpl();

			if (geomNode.gmlId != null)
				multiSurface.setId(geomNode.gmlId);

			for (GeometryNode childNode : geomNode.childNodes) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(childNode, isSetOrientableSurface);

				if (geomMember != null) {
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();
					SurfaceProperty surfaceMember = new SurfacePropertyImpl();

					if (absGeom != null) {
						switch (geomMember.getType()) {
						case POLYGON:
						case ORIENTABLESURFACE:
						case COMPOSITESURFACE:
						case TRIANGULATEDSURFACE:
							surfaceMember.setSurface((AbstractSurface)absGeom);
							break;
						default:
							surfaceMember = null;
						}
					} else {
						surfaceMember.setHref(geomMember.getTarget());
					}

					if (surfaceMember != null)
						multiSurface.addSurfaceMember(surfaceMember);
				}
			}

			if (multiSurface.getSurfaceMember() != null)
				return new DBSurfaceGeometryResult(multiSurface);

			return null;
		}

		// triangulatedSurface
		else if (surfaceGeometryType == GMLClass.TRIANGULATEDSURFACE) {
			TriangulatedSurface triangulatedSurface = new TriangulatedSurfaceImpl();

			if (geomNode.gmlId != null)
				triangulatedSurface.setId(geomNode.gmlId);

			TrianglePatchArrayProperty triangleArray = new TrianglePatchArrayPropertyImpl();
			for (GeometryNode childNode : geomNode.childNodes) {
				DBSurfaceGeometryResult geomMember = rebuildGeometry(childNode, isSetOrientableSurface);

				if (geomMember != null) {
					// we are only expecting polygons...
					AbstractGeometry absGeom = geomMember.getAbstractGeometry();					

					switch (geomMember.getType()) {
					case POLYGON:
						// we do not have to deal with xlinks here...
						if (absGeom != null) {
							// convert polygon to trianglePatch
							Triangle triangle = new TriangleImpl();
							Polygon polygon = (Polygon)absGeom;

							if (polygon.getExterior() != null) {
								LinearRing exteriorRing = (LinearRing)polygon.getExterior().getRing();
								if (exteriorRing != null) {
									triangle.setExterior(polygon.getExterior());
									triangleArray.addTriangle(triangle);
								}								
							}							
						}

						break;
					}
				}
			}

			if (triangleArray.getTriangle() != null && !triangleArray.getTriangle().isEmpty()) {
				triangulatedSurface.setTrianglePatches(triangleArray);

				// check whether we have to embrace the compositeSurface with an orientableSurface
				if (initOrientableSurface || (isSetOrientableSurface && !geomNode.isReverse)) {
					OrientableSurface orientableSurface = new OrientableSurfaceImpl();				
					SurfaceProperty surfaceProperty = new SurfacePropertyImpl();
					surfaceProperty.setSurface(triangulatedSurface);
					orientableSurface.setBaseSurface(surfaceProperty);
					orientableSurface.setOrientation("-");
					dbExporterManager.updateGeometryCounter(GMLClass.ORIENTABLESURFACE);

					return new DBSurfaceGeometryResult(orientableSurface);
				} else
					return new DBSurfaceGeometryResult(triangulatedSurface);
			}

			return null;
		}

		return null;
	}


	@Override
	public DBExporterEnum getDBExporterType() {
		return DBExporterEnum.SURFACE_GEOMETRY;
	}

	private class GeometryNode {
		protected long id;
		protected String gmlId;
		protected long parentId;
		protected boolean isSolid;
		protected boolean isComposite;
		protected boolean isTriangulated;
		protected boolean isXlink;
		protected boolean isReverse;
		protected JGeometry geometry;
		protected Vector<GeometryNode> childNodes;

		public GeometryNode() {
			childNodes = new Vector<GeometryNode>();
		}
	}

	private class GeometryTree {
		long root;
		private HashMap<Long, GeometryNode> geometryTree;

		public GeometryTree() {
			geometryTree = new HashMap<Long, GeometryNode>();
		}

		public void insertNode(GeometryNode geomNode, long parentId) {

			if (parentId == 0)
				root = geomNode.id;

			if (geometryTree.containsKey(geomNode.id)) {

				// we have inserted a pseudo node previously
				// so fill that one with life...
				GeometryNode pseudoNode = geometryTree.get(geomNode.id);
				pseudoNode.id = geomNode.id;
				pseudoNode.gmlId = geomNode.gmlId;
				pseudoNode.parentId = geomNode.parentId;
				pseudoNode.isSolid = geomNode.isSolid;
				pseudoNode.isComposite = geomNode.isComposite;
				pseudoNode.isTriangulated = geomNode.isTriangulated;
				pseudoNode.isXlink = geomNode.isXlink;
				pseudoNode.isReverse = geomNode.isReverse;
				pseudoNode.geometry = geomNode.geometry;

				geomNode = pseudoNode;

			} else {
				// identify hierarchy nodes and place them
				// into the tree
				if (geomNode.geometry == null || parentId == 0)
					geometryTree.put(geomNode.id, geomNode);
			}

			// make the node known to its parent...
			if (parentId != 0) {
				GeometryNode parentNode = geometryTree.get(parentId);

				if (parentNode == null) {
					// there is no entry so far, so lets create a
					// pseude node
					parentNode = new GeometryNode();
					geometryTree.put(parentId, parentNode);
				}

				parentNode.childNodes.add(geomNode);
			}
		}

		public GeometryNode getNode(long entryId) {
			return geometryTree.get(entryId);
		}
	}
}
