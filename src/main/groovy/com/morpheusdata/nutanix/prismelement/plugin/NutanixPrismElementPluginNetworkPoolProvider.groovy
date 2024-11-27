/*
 *  Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the PLUGIN CORE SOURCE LICENSE (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://raw.githubusercontent.com/gomorpheus/morpheus-plugin-core/v1.0.x/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.IPAMProvider
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkPool
import com.morpheusdata.model.NetworkPoolIp
import com.morpheusdata.model.NetworkPoolServer
import com.morpheusdata.model.NetworkPoolType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse

/**
 * Network pool provider for Nutanix Prism Element
 *
 * This assumes that a network pool with DHCP has been setup in the Nutanix cluster.
 * For now, the Morpheus side doesn't create/edit nutanix network pools
 */
class NutanixPrismElementPluginNetworkPoolProvider implements IPAMProvider {
	public static final String NETWORK_POOL_PROVIDER_CODE = 'nutanix-prism-element-network-pool-provider'
	public static final String NETWORK_POOL_PROVIDER_NAME = 'Nutanix Prism Element'

	protected MorpheusContext morpheusContext
	protected Plugin plugin

	NutanixPrismElementPluginNetworkPoolProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super()
		this.@plugin = plugin
		this.@morpheusContext = morpheusContext
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse verifyNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		ServiceResponse.success(poolServer)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse createNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse updateNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	void refresh(NetworkPoolServer poolServer) {
		// no-op
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse initializeNetworkPoolServer(NetworkPoolServer poolServer, Map opts) {
		ServiceResponse.success(poolServer)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse createHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp, NetworkDomain domain, Boolean createARecord, Boolean createPtrRecord) {
		// host records aren't editable for this plugin
		ServiceResponse.error("unsupported")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse updateHostRecord(NetworkPoolServer poolServer, NetworkPool networkPool, NetworkPoolIp networkPoolIp) {
		// host records aren't editable for this plugin
		ServiceResponse.error("unsupported")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteHostRecord(NetworkPool networkPool, NetworkPoolIp poolIp, Boolean deleteAssociatedRecords) {
		// host records aren't editable for this plugin
		ServiceResponse.error("unsupported")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<NetworkPoolType> getNetworkPoolTypes() {
		def networkPoolType = new NetworkPoolType(
			code: 'nutanix',
			name: 'Nutanix',
			creatable: false,
			description: 'Nutanix network ip pool',
			rangeSupportsCidr: false,
			hostRecordEditable: false
		)

		[networkPoolType]
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	List<OptionType> getIntegrationOptionTypes() {
		[]
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Icon getIcon() {
		new Icon(path: 'nutanix.svg', darkPath: 'nutanix-dark.svg')
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	MorpheusContext getMorpheus() {
		this.@morpheusContext
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Plugin getPlugin() {
		this.@plugin
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getCode() {
		NETWORK_POOL_PROVIDER_CODE
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getName() {
		NETWORK_POOL_PROVIDER_NAME
	}
}
