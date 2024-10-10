/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import io.reactivex.rxjava3.core.Observable

class NutanixPrismElementVersionDatasetProvider extends AbstractDatasetProvider<Map, String> {
	public static final PROVIDER_NAME = "Nutanix Version Provider"
	public static final PROVIDER_NAMESPACE = "com.morpheusdata.nutanix.prismelement.plugin"
	public static final PROVIDER_KEY = "supportedVmmApiVersions"
	public static final PROVIDER_DESCRIPTION = "The API version to use for interacting with Nutanix Prism Element"

	static final MEMBERS = [
		[name: 'v0.8', value: 'v0.8'],
		[name: 'v1', value: 'v1'],
		[name: 'v2.0', value: 'v2.0'],
		[name: 'v3.0', value: 'v3']
	]

	NutanixPrismElementVersionDatasetProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	DatasetInfo getInfo() {
		new DatasetInfo(
			name: PROVIDER_NAME,
			namespace: PROVIDER_NAMESPACE,
			key: PROVIDER_KEY,
			description: PROVIDER_DESCRIPTION,
		)
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	Class<Map> getItemType() {
		return Map.class
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	Observable<Map> list(DatasetQuery query) {
		return Observable.fromIterable(MEMBERS)
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	Observable<Map> listOptions(DatasetQuery query) {
		return Observable.fromIterable(MEMBERS)
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	Map fetchItem(Object value) {
		def rtn = null
		if (value instanceof String) {
			rtn = value
		}
		return item(rtn)
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	Map item(String value) {
		def rtn = MEMBERS.find { it.value == value }
		return rtn
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	String itemName(Map item) {
		return item.name
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	String itemValue(Map item) {
		return (Long) item.value
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	boolean isPlugin() {
		return true
	}
}
