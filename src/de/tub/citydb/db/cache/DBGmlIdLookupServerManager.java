package de.tub.citydb.db.cache;

import java.sql.SQLException;
import java.util.HashMap;

import de.tub.citydb.event.EventDispatcher;
import de.tub.citygml4j.model.citygml.CityGMLClass;

public class DBGmlIdLookupServerManager {
	private final HashMap<DBGmlIdLookupServerEnum, GmlIdLookupServer> serverMap;
	private final EventDispatcher eventDispatcher;

	public DBGmlIdLookupServerManager(EventDispatcher eventDispatcher) {
		this.eventDispatcher = eventDispatcher;
		serverMap = new HashMap<DBGmlIdLookupServerEnum, GmlIdLookupServer>();
	}

	public void initServer(
		DBGmlIdLookupServerEnum serverType,
		DBCacheModel model,
		int cacheSize,
		float drainFactor,
		int concurrencyLevel) throws SQLException {

		serverMap.put(serverType, new GmlIdLookupServer(
				model,
				cacheSize,
				drainFactor,
				concurrencyLevel,
				eventDispatcher
		));
	}

	public GmlIdLookupServer getLookupServer(CityGMLClass type) {
		DBGmlIdLookupServerEnum lookupServer;

		switch (type) {
		case GMLGEOMETRY:
			lookupServer = DBGmlIdLookupServerEnum.GEOMETRY;
			break;
		default:
			lookupServer = DBGmlIdLookupServerEnum.FEATURE;
		}

		return serverMap.get(lookupServer);
	}
}
