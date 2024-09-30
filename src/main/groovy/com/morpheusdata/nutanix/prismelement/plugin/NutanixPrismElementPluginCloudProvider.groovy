package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudFolder
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Cloud provider for the Nutanix Prism Element Plugin
 *
 * TODO: Omitted the 'securityTypes', 'networkServerTypes' and 'serverGroupTypes' from the embedded computeZoneType
 * 		 seed. Once there are hooks in the providers, we should fill them in.
 */
@Slf4j
class NutanixPrismElementPluginCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'nutanix'
	public static final String CLOUD_PROVIDER_NAME = 'Nutanix Prism Element'
	private final oneGB = (1024 * 1024 * 1024) as Long

	protected MorpheusContext context
	protected Plugin plugin

    NutanixPrismElementPluginCloudProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.@plugin = plugin
		this.@context = ctx
	}

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return '''Nutanix Prism Element is a comprehensive management solution for hyper-converged infrastructure, 
providing intuitive, centralized control over storage, compute, and networking resources. 
It streamlines operations with powerful automation, analytics, and one-click simplicity.'''
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:'nutanix.svg', darkPath:'nutanix-dark.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'nutanix-circular.svg', darkPath:'nutanix-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
        Collection<OptionType> options = []
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
        return options
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
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

		def childNetwork = context.async.network.type.find(new DataQuery().withFilter('code', 'childNetwork')).blockingGet()
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
		Collection<StorageVolumeType> volumeTypes = []

		volumeTypes << new StorageVolumeType(
				code: 'nutanix-scsi',
				externalId: 'nutanix_SCSI',
				displayName: 'Nutanix SCSI',
				name: 'scsi',
				description: 'Nutanix - SCSI',
				displayOrder: 1,
				defaultType: true,
				minStorage: oneGB,
				allowSearch: true,
		)

		volumeTypes << new StorageVolumeType(
				code: 'nutanix-sata',
				externalId: 'nutanix_SATA',
				displayName: 'Nutanix SATA',
				name: 'sata',
				description: 'Nutanix - SATA',
				displayOrder: 2,
				defaultType: true,
				minStorage: oneGB,
				allowSearch: true,
		)

		volumeTypes << new StorageVolumeType(
				code: 'nutanix-ide',
				externalId: 'nutanix_IDE',
				displayName: 'Nutanix IDE',
				name: 'ide',
				description: 'Nutanix - IDE',
				displayOrder: 3,
				defaultType: true,
				minStorage: oneGB,
				allowSearch: true,
		)

		volumeTypes
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
				provisionTypeCode : CLOUD_PROVIDER_CODE)

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
				code:'nutanixKubeMaster',
				name:'Nutanix Kubernetes Master',
				description:'',
				platform:'linux',
				nodeType:'kube-master',
				controlSuspend:true,
				creatable:true,
				computeService:'nutanixComputeService',
				displayOrder:15,
				containerHypervisor:true,
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

		serverTypes
	}

	/**
	 * Validates the submitted cloud information to make sure it is functioning correctly.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param cloudInfo cloud
	 * @param validateCloudRequest Additional validation information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc.
	 * @param cloudInfo cloud
	 * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
	 * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
	 * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
	 */
	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
	}

	/**
	 * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
	 * @param cloudInfo instance of the cloud object that is being removed.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Returns whether the cloud supports {@link CloudPool}
	 * @return Boolean
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Network}
	 * @return Boolean
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
	 * Returns whether a cloud supports {@link CloudFolder}
	 * @return Boolean
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Datastore}
	 * @return Boolean
	 */
	@Override
	Boolean hasDatastores() {
		return true
	}

	/**
	 * Returns whether a cloud supports bare metal VMs
	 * @return Boolean
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
	 * Indicates if the cloud supports the distributed worker functionality
	 * @return Boolean
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return true
	}

	/**
	 * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
	 * @param computeServer server to start
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
	 * @param computeServer server to stop
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be deleted from the Cloud.
	 * @param computeServer server to delete
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
	 * This is only really necessary if the provision type code is the exact same as the cloud code.
	 * @return the provision provider code
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return CLOUD_PROVIDER_CODE // cloud code and provision code match for existing plugin
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
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
