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
package org.citydb.config.project.resources;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

@XmlType(name="UIDCacheType", propOrder={
		"feature",
		"geometry"		
})
public class UIDCache {
	@XmlElement(required=true)
	private UIDCacheConfig feature;
	@XmlElement(required=true)
	private UIDCacheConfig geometry;

	public UIDCache() {
		feature = new UIDCacheConfig();
		geometry = new UIDCacheConfig();
	}

	public UIDCacheConfig getFeature() {
		return feature;
	}

	public void setFeature(UIDCacheConfig feature) {
		if (feature != null)
			this.feature = feature;
	}

	public UIDCacheConfig getGeometry() {
		return geometry;
	}

	public void setGeometry(UIDCacheConfig geometry) {
		if (geometry != null)
			this.geometry = geometry;
	}

}
