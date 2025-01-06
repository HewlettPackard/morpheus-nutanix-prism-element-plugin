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


import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.nutanix.prismelement.plugin.backup.NutanixPrismElementBackupProvider
import com.morpheusdata.nutanix.prismelement.plugin.cloud.NutanixPrismElementCloudProvider
import com.morpheusdata.nutanix.prismelement.plugin.dataset.NutanixPrismElementImageStoreDatasetProvider
import com.morpheusdata.nutanix.prismelement.plugin.dataset.NutanixPrismElementProvisionImageDatasetProvider
import com.morpheusdata.nutanix.prismelement.plugin.dataset.NutanixPrismElementVirtualImageDatasetProvider
import com.morpheusdata.nutanix.prismelement.plugin.network.NutanixPrismElementNetworkPoolProvider
import com.morpheusdata.nutanix.prismelement.plugin.provision.NutanixPrismElementProvisionProvider

@SuppressWarnings("unused")
// picked up by plugin framework
class NutanixPrismElementPlugin extends Plugin {
	protected ProvisionProvider provisionProvider
	private CloudProvider cloudProvider

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getCode() {
		return 'nutanix-prism-element-plugin'
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	void initialize() {
		this.setName("Nutanix Prism Element")

		cloudProvider = new NutanixPrismElementCloudProvider(this, this.morpheus)
		def imageStoreDatasetProvider = new NutanixPrismElementImageStoreDatasetProvider(this, this.morpheus)
		def provisionImageDatasetProvider = new NutanixPrismElementProvisionImageDatasetProvider(this, this.morpheus)
		def virtualImageDatasetProvider = new NutanixPrismElementVirtualImageDatasetProvider(this, this.morpheus)
		provisionProvider = new NutanixPrismElementProvisionProvider(this, this.morpheus)
		def networkPoolProvider = new NutanixPrismElementNetworkPoolProvider(this, this.morpheus)
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
	 * {@inheritDoc}
	 */
	@Override
	void onDestroy() {
		// we need to override the instance type that was modified by the plugin.
		// This includes optionTypes that changed.
		List<String> seedsToRun = [
			"application.NutanixZoneTypeSeed",
			"application.ProvisionTypeNutanixSeed",
			"application.NetworkTypeSeed",
		]
		morpheus.services.seed.reinstallSeedData(seedsToRun) // needs to be synchronous to prevent seeds from running during plugin install
	}
}
