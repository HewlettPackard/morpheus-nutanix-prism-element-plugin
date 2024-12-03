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

package com.morpheusdata.nutanix.prismelement.plugin.cloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.nutanix.prismelement.plugin.dataset.NutanixPrismElementImageStoreDatasetProvider
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPlugin
import com.morpheusdata.nutanix.prismelement.plugin.provision.NutanixPrismElementProvisionProvider
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.ContainersSync
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.HostsSync
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.ImagesSync
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.NetworkSync
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.SnapshotsSync
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.VirtualMachinesSync
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementComputeUtility
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementStorageUtility
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.security.MessageDigest

/**
 * Cloud provider for the Nutanix Prism Element Plugin
 *
 * TODO: Omitted the 'securityTypes', 'networkServerTypes' and 'serverGroupTypes' from the embedded computeZoneType
 * 		 seed. Once there are hooks in the providers, we should fill them in.
 */
@Slf4j
class NutanixPrismElementCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'nutanix'
	public static final String CLOUD_PROVIDER_NAME = 'Nutanix Prism Element'

	protected MorpheusContext morpheusContext
	protected NutanixPrismElementPlugin plugin

	NutanixPrismElementCloudProvider(NutanixPrismElementPlugin plugin, MorpheusContext morpheusContext) {
		super()
		this.@plugin = plugin
		this.@morpheusContext = morpheusContext
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getDescription() {
		return '''Nutanix Prism Element is a comprehensive management solution for hyper-converged infrastructure,
providing intuitive, centralized control over storage, compute, and networking resources.
It streamlines operations with powerful automation, analytics, and one-click simplicity.'''
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Icon getIcon() {
		return new Icon(path: 'nutanix.svg', darkPath: 'nutanix-dark.svg')
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path: 'nutanix-circular.svg', darkPath: 'nutanix-circular-dark.svg')
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
			name: 'Api Url',
			code: 'nutanix-prism-element-api-url',
			displayOrder: 0,
			fieldContext: 'domain',
			fieldName: 'serviceUrl',
			fieldCode: 'gomorpheus.label.apiUrl',
			required: true,
			inputType: OptionType.InputType.TEXT,
			placeHolder: 'https://nutanix.domain.com:9440'
		)
		options << new OptionType(
			code: 'zoneType.nutanix.credential',
			inputType: OptionType.InputType.CREDENTIAL,
			name: 'Credentials',
			fieldName: 'type',
			fieldLabel: 'Credentials',
			fieldContext: 'credential',
			fieldCode: 'gomorpheus.label.credentials',
			fieldSet: '',
			fieldGroup: 'Connection Config',
			required: true,
			global: false,
			helpBlock: '',
			defaultValue: 'local',
			displayOrder: 1,
			optionSource: 'credentials',
			config: '{"credentialTypes":["username-password"]}'
		)
		options << new OptionType(
			name: 'Username',
			code: 'nutanix-prism-element-username',
			displayOrder: 2,
			fieldContext: 'domain',
			fieldName: 'serviceUsername',
			fieldCode: 'gomorpheus.label.username',
			required: true,
			inputType: OptionType.InputType.TEXT,
			localCredential: true,
		)

		options << new OptionType(
			name: 'Password',
			code: 'nutanix-prism-element-password',
			displayOrder: 3,
			fieldContext: 'domain',
			fieldName: 'servicePassword',
			fieldCode: 'gomorpheus.label.password',
			required: true,
			inputType: OptionType.InputType.PASSWORD,
			localCredential: true,
		)

		options << new OptionType(
			name: 'Import Existing',
			code: 'nutanix-prism-element-import-existing',
			displayOrder: 5,
			fieldContext: 'config',
			fieldName: 'importExisting',
			fieldCode: 'gomorpheus.label.inventoryExistingInstances',
			inputType: OptionType.InputType.CHECKBOX,
		)

		options << new OptionType(
			name: 'Enable VNC',
			code: 'nutanix-prism-element-enable-hypervisor-console',
			displayOrder: 6,
			fieldContext: 'config',
			fieldName: 'enableVnc',
			fieldCode: 'gomorpheus.label.enableHyperVConsole',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.CHECKBOX,
		)

		options << new OptionType(
			name: 'Default Image Store',
			code: 'nutanix-prism-element-default-image-store',
			displayOrder: 7,
			fieldContext: 'config',
			fieldName: 'imageStoreId',
			fieldCode: 'gomorpheus.label.defaultImageStore',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSourceType: NutanixPrismElementImageStoreDatasetProvider.PROVIDER_NAMESPACE,
			optionSource: NutanixPrismElementImageStoreDatasetProvider.PROVIDER_KEY,
		)

		options << new OptionType(
			name: 'Enable Network Type Selection',
			code: 'nutanix-prism-element-enable-network-type-selection',
			displayOrder: 8,
			fieldContext: 'config',
			fieldName: 'enableNetworkTypeSelection',
			fieldCode: 'gomorpheus.label.enableNetworkTypeSelection',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.CHECKBOX,
		)

		return options
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		Collection<BackupProvider> providers = []
		return providers
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		Collection<NetworkType> networks = []

		def childNetwork = morpheusContext.async.network.type.find(new DataQuery().withFilter('code', 'childNetwork')).blockingGet()
		if (childNetwork != null) {
			networks << childNetwork
		} else {
			log.error("Unable to find NetworkType dependency: 'childNetwork'")
		}

		networks << new NetworkType(
			code: 'nutanixVlan',
			name: 'VLAN',
			description: '',
			externalType: 'Network',
			cidrEditable: true,
			dhcpServerEditable: true,
			dnsEditable: true,
			gatewayEditable: true,
			vlanIdEditable: true,
			canAssignPool: true,
		)

		networks << new NetworkType(
			code: 'nutanixManagedVlan',
			name: 'Managed VLAN',
			description: '',
			externalType: 'Network',
			cidrEditable: true,
			dhcpServerEditable: true,
			dnsEditable: true,
			gatewayEditable: true,
			vlanIdEditable: true,
			canAssignPool: true,
		)

		networks
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		[]
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		NutanixPrismElementStorageUtility.getDefaultStorageVolumes()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		[]
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = []

		serverTypes << new ComputeServerType(
			code: 'nutanixMetalHypervisor',
			name: 'Nutanix Hypervisor - Metal',
			platform: 'linux',
			nodeType: 'nutanix-node',
			externalDelete: false,
			managed: false,
			controlPower: false,
			computeService: 'nutanixComputeService',
			displayOrder: 1,
			hasAutomation: false,
			vmHypervisor: true,
			bareMetalHost: true,
			agentType: null,
			provisionTypeCode: CLOUD_PROVIDER_CODE)

		serverTypes << new ComputeServerType(
			code: 'nutanixVm',
			name: 'Nutanix Instance',
			description: '',
			platform: 'linux',
			nodeType: 'morpheus-vm-node',
			computeService: 'nutanixComputeService',
			displayOrder: 0,
			guestVm: true,
			provisionTypeCode: CLOUD_PROVIDER_CODE)

		// Note: the definition is commented out in embedded. Keeping in case it needs to be re-enabled in the future
//    		serverTypes << new ComputeServerType(
//    			code: 'nutanixWindowsVm',
//    			name: 'Nutanix Instance - Windows',
//    			description: '',
//    			platform: 'windows',
//    			nodeType: 'morpheus-windows-vm-node',
//    			reconfigureSupported: true,
//    			enabled: true,
//    			selectable: false,
//    			externalDelete: true,
//    			managed: true,
//    			controlPower: true,
//    			controlSuspend: false,
//    			creatable: false,
//    			computeService: 'nutanixComputeService',
//    			displayOrder: 0,
//    			hasAutomation: true,
//    			containerHypervisor: false,
//    			bareMetalHost: false,
//    			vmHypervisor: false,
//    			agentType: ComputeServerType.AgentType.guest,
//    			guestVm: true,
//    			provisionTypeCode: CLOUD_PROVIDER_CODE)

		serverTypes << new ComputeServerType(
			code: 'nutanixUnmanaged',
			name: 'Nutanix Instance',
			description: 'nutanix vm',
			platform: 'linux',
			nodeType: 'unmanaged',
			managed: false,
			computeService: 'nutanixComputeService',
			displayOrder: 99,
			hasAutomation: false,
			agentType: null,
			managedServerType: 'nutanixVm',
			guestVm: true,
			provisionTypeCode: CLOUD_PROVIDER_CODE,
		)

		serverTypes << new ComputeServerType(
			code: 'nutanixLinux',
			name: 'Nutanix Docker Host',
			description: '',
			platform: 'linux',
			nodeType: 'morpheus-node',
			computeService: 'nutanixComputeService',
			displayOrder: 20,
			containerHypervisor: true,
			agentType: ComputeServerType.AgentType.host,
			containerEngine: ComputeServerType.ContainerEngine.docker,
			computeTypeCode: 'docker-host',
			provisionTypeCode: CLOUD_PROVIDER_CODE,
		)

		//kubernetes
		serverTypes << new ComputeServerType(
			code: 'nutanixKubeMaster',
			name: 'Nutanix Kubernetes Master',
			description: '',
			platform: 'linux',
			nodeType: 'kube-master',
			controlSuspend: true,
			creatable: true,
			computeService: 'nutanixComputeService',
			displayOrder: 15,
			containerHypervisor: true,
			agentType: ComputeServerType.AgentType.host,
			containerEngine: ComputeServerType.ContainerEngine.docker,
			provisionTypeCode: CLOUD_PROVIDER_CODE,
			computeTypeCode: 'kube-master',
		)

		serverTypes << new ComputeServerType(
			code: 'nutanixKubeWorker',
			name: 'Nutanix Kubernetes Worker',
			description: '',
			platform: 'linux',
			nodeType: 'kube-worker',
			controlSuspend: true,
			creatable: true,
			computeService: 'nutanixComputeService',
			displayOrder: 16,
			containerHypervisor: true,
			agentType: ComputeServerType.AgentType.guest,
			containerEngine: ComputeServerType.ContainerEngine.docker,
			provisionTypeCode: CLOUD_PROVIDER_CODE,
			computeTypeCode: 'kube-worker',
		)

		def existingComputeServiceTypes = [
			'selfManagedLinux',
			'unmanaged',
			'managed',
			'unmanagedWindows',
			'managedWindows',
			'unmanagedMac',
			'managedMac',
		]

		existingComputeServiceTypes.each {
			def t = morpheusContext.async.cloud.findComputeServerTypeByCode(it).blockingGet()
			if (t != null) {
				serverTypes << t
			}
		}

		serverTypes
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if (cloudInfo) {
				String username = ""
				String password = ""
				def url = (cloudInfo.serviceUrl ?: cloudInfo.configMap.apiUrl) as String
				if (validateCloudRequest.credentialType?.toString()?.isNumber()) {
					AccountCredential accountCredential = morpheus.async.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if (validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword ?: cloudInfo.servicePassword ?: cloudInfo.configMap.password
					username = validateCloudRequest.credentialUsername ?: cloudInfo.servicePassword ?: cloudInfo.configMap.username
				} else if (validateCloudRequest.credentialType == 'local') {
					if (validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.servicePassword ?: cloudInfo.configMap.password
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.serviceUsername ?: cloudInfo.configMap.username
				}

				if (username?.isBlank()) {
					return ServiceResponse.error('Enter a username')
				} else if (password?.isBlank()) {
					return ServiceResponse.error('Enter a password')
				} else if (url?.isBlank()) {
					return ServiceResponse.error('Enter an api url')
				} else {
					def authConfig = [
						username  : username,
						password  : password,
						apiUrl    : url,
					]
					HttpApiClient apiClient = new HttpApiClient()
					apiClient.networkProxy = cloudInfo.apiProxy
					try {
						def containerList = NutanixPrismElementApiService.listContainers(apiClient, authConfig)
						if (containerList.success == true) {
							return ServiceResponse.success()
						} else {
							return ServiceResponse.error('Invalid credentials')
						}
					} finally {
						apiClient.shutdownClient()
					}
				}
			} else {
				return ServiceResponse.error('No cloud found')
			}
		} catch (e) {
			log.error('Error validating cloud', e)
			return ServiceResponse.error('Error validating cloud')
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		log.info("initializeCloud: ${cloudInfo}")
		if (cloudInfo) {
			if (cloudInfo.enabled) {
				return refresh(cloudInfo)
			}
		} else {
			return ServiceResponse.error('No cloud found')
		}

		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		log.info("refresh: ${cloudInfo}")

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = cloudInfo.apiProxy
		try {
			def syncDate = new Date()
			def apiUrl = NutanixPrismElementApiService.getNutanixApiUrl(cloudInfo)
			def apiUrlObj = new URL(apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)

			def proxySettings = cloudInfo.apiProxy
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, proxySettings)
			log.debug("nutanix online: ${apiHost} ${hostOnline}")
			if (hostOnline) {
				def testResults = NutanixPrismElementApiService.testConnection(client, [zone: cloudInfo])

				if (testResults.success) {
					def regionCode = calculateRegionCode(cloudInfo)
					if (cloudInfo.regionCode != regionCode) {
						convertOldRegionCodes(cloudInfo.regionCode, regionCode)
						cloudInfo.regionCode = regionCode
						morpheusContext.async.cloud.save(cloudInfo).blockingGet()
					}
					morpheusContext.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.syncing, null, syncDate)
					new NetworkSync(morpheusContext, cloudInfo, client).execute()
					new ContainersSync(morpheusContext, cloudInfo, client).execute()
					new ImagesSync(morpheusContext, cloudInfo, client).execute()
					new HostsSync(morpheusContext, cloudInfo, client).execute()
					new VirtualMachinesSync(morpheusContext, cloudInfo, client, plugin.provisionProvider.getComputeServerInterfaceTypes(), plugin.cloudProvider.getComputeServerTypes()).execute()
					new SnapshotsSync(morpheusContext, cloudInfo, client).execute()
					morpheusContext.services.operationNotification.clearZoneAlarm(cloudInfo)
					morpheusContext.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.ok, null, syncDate)
				} else {
					if (testResults.invalidLogin) {
						morpheusContext.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.offline, 'nutanix invalid credentials', syncDate)
						morpheusContext.services.operationNotification.createZoneAlarm(cloudInfo, 'nutanix invalid credentials')
					} else {
						morpheusContext.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.offline, 'nutanix host not reachable', syncDate)
						morpheusContext.services.operationNotification.createZoneAlarm(cloudInfo, 'nutanix invalid credentials')
					}
				}
			} else {
				morpheusContext.async.cloud.updateCloudStatus(cloudInfo, Cloud.Status.offline, 'nutanix host not reachable', syncDate)
				morpheusContext.services.operationNotification.createZoneAlarm(cloudInfo, 'nutanix host not reachable')
			}
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
			return ServiceResponse.error()
		} finally {
			client.shutdownClient()
		}
		return ServiceResponse.success()
	}

	static String calculateRegionCode(Cloud cloudInfo) {
		def apiUrl = cloudInfo?.getConfigProperty('apiUrl')
		def regionString = "${apiUrl}"
		MessageDigest md = MessageDigest.getInstance("SHA3-224")
		md.update(regionString.bytes)
		byte[] checksum = md.digest()
		return checksum.encodeHex().toString()
	}

	void convertOldRegionCodes(String oldRegionCode, String newRegionCode) {
		if (oldRegionCode && newRegionCode) {
			List<VirtualImageLocation> imageLocations = morpheusContext.async.virtualImage.location.list(new DataQuery().withFilter("imageRegion", oldRegionCode))
				.filter { it.imageRegion == oldRegionCode }
				.map {
					it.imageRegion = newRegionCode
					it
				}
				.collect()

			def imageLocationSaveResult = morpheusContext.services.virtualImage.location.bulkSave(imageLocations)
			if (imageLocationSaveResult.hasFailures()) {
				log.error("Failed to update new region code for virtual image locations: ${imageLocationSaveResult.failedItems.code}")
			}

			List<VirtualImage> images = morpheusContext.async.virtualImage.list(new DataQuery().withFilter("imageRegion", oldRegionCode))
				.filter { it.imageRegion == oldRegionCode }
				.map {
					it.imageRegion = newRegionCode
					it
				}
				.collect()

			def imageSaveResult = morpheusContext.services.virtualImage.bulkSave(images)
			if (imageSaveResult.hasFailures()) {
				log.error("Failed to update new region code for virtual images: ${imageSaveResult.failedItems.code}")
			}

			List<ServicePlan> servicePlans = morpheusContext.async.servicePlan.list(new DataQuery().withFilter("regionCode", oldRegionCode))
				.filter { it.regionCode == oldRegionCode }
				.map {
					it.regionCode = newRegionCode
					it
				}
				.collect()

			def servicePlanSaveResult = morpheusContext.services.servicePlan.bulkSave(servicePlans)
			if (servicePlanSaveResult.hasFailures()) {
				log.error("Failed to update new region code for service plans: ${servicePlanSaveResult.failedItems.code}")
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean canCreateNetworks() {
		true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasDatastores() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasBareMetal() {
		return false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasCloudInit() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		HttpApiClient client = new HttpApiClient()
		return NutanixPrismElementComputeUtility.doStart(client, computeServer, computeServer.cloud, "startServer")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		HttpApiClient client = new HttpApiClient()
		return NutanixPrismElementComputeUtility.doStop(client, computeServer, computeServer.cloud, "stopServer")
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		log.debug("deleteServer: ${computeServer}")
		def rtn = [success: false]
		try {
			Cloud cloud = computeServer.cloud
			def serverId = computeServer.externalId
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def removeOpts = [zone: cloud]
			def vmResults = NutanixPrismElementApiService.loadVirtualMachine(client, removeOpts, serverId)
			if (vmResults.success) {
				if (vmResults.results?.vm_logical_timestamp) {
					removeOpts.timestamp = vmResults.results?.vm_logical_timestamp
				}
				def stopResults = NutanixPrismElementApiService.stopVm(client, removeOpts, serverId)
				if (stopResults.success) {
					def removeResults = NutanixPrismElementApiService.deleteServer(client, removeOpts, serverId)
					if (removeResults.success) {
						rtn.success = true
					}
				}
			}
		} catch (e) {
			rtn.msg = "Error deleting server: ${e.message}"
			log.error("deleteServer error: ${e}", e)
		}
		return new ServiceResponse(rtn)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return NutanixPrismElementProvisionProvider.PROVISION_PROVIDER_CODE
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@morpheusContext
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return CLOUD_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return CLOUD_PROVIDER_NAME
	}
}
