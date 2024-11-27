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
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.nutanix.prismelement.plugin.backup.NutanixPrismElementBackupProvider

@SuppressWarnings("unused")
// picked up by plugin framework
class NutanixPrismElementPlugin extends Plugin {
	protected ProvisionProvider provisionProvider
	private CloudProvider cloudProvider

	@Override
	String getCode() {
		return 'nutanix-prism-element-plugin'
	}

	@Override
	void initialize() {
		this.setName("Nutanix Prism Element Plugin")

		cloudProvider = new NutanixPrismElementPluginCloudProvider(this, this.morpheus)
		def imageStoreDatasetProvider = new NutanixPrismElementImageStoreDatasetProvider(this, this.morpheus)
		def provisionImageDatasetProvider = new NutanixPrismElementProvisionImageDatasetProvider(this, this.morpheus)
		def virtualImageDatasetProvider = new NutanixPrismElementVirtualImageDatasetProvider(this, this.morpheus)
		provisionProvider = new NutanixPrismElementPluginProvisionProvider(this, this.morpheus)
		def networkPoolProvider = new NutanixPrismElementPluginNetworkPoolProvider(this, this.morpheus)
		def backupProvider = new NutanixPrismElementBackupProvider(this, morpheus)

		this.registerProviders(
			backupProvider,
			this.cloudProvider,
			imageStoreDatasetProvider,
			provisionImageDatasetProvider,
			networkPoolProvider,
			this.provisionProvider,
			virtualImageDatasetProvider,
		)
	}

	/**
	 * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
	 */
	@Override
	void onDestroy() {
		//nothing to do for now
	}

	static getAuthConfig(MorpheusContext morpheusContext, Cloud cloud) {
		if (!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = morpheusContext.async.cloud.loadCredentials(cloud.id).blockingGet()
				cloud.accountCredentialLoaded = true
				cloud.accountCredentialData = accountCredential?.data
			} catch (e) {
			}
		}

		def config = [
			basePath  : '/api/nutanix/v3/',
			apiVersion: 'v2.0',
			apiUrl    : (cloud.serviceUrl ?: cloud.configMap.apiUrl),
			apiNumber : 2.0,
		]
		if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			config.username = cloud.accountCredentialData['username']
		} else {
			config.username = cloud.serviceUsername ?: cloud.configMap.username
		}
		if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			config.password = cloud.accountCredentialData['password']
		} else {
			config.password = cloud.servicePassword ?: cloud.configMap.password
		}

		return config
	}
}
