package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.VirtualImageType
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementStorageUtility
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse

class NutanixPrismElementPluginProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider {
	public static final String PROVISION_PROVIDER_CODE = 'nutanix-prism-element-provision-provider'
	public static final String PROVISION_PROVIDER_NAME = 'Nutanix Prism Element'

	protected MorpheusContext context
	protected Plugin plugin

	NutanixPrismElementPluginProvisionProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	@Override
	String getProvisionTypeCode() {
		return NutanixPrismElementPluginCloudProvider.CLOUD_PROVIDER_CODE // cloud code and provision code match for existing plugin
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'nutanix-circular.svg', darkPath:'nutanix-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
				name:'skip agent install',
				code: 'provisionType.general.noAgent',
				category:'provisionType.amazon',
				fieldName: 'noAgent',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldContext: 'config',
				fieldGroup: "Advanced Options",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				displayOrder: 104,
				inputType: OptionType.InputType.CHECKBOX,
				helpBlock: 'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.')
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []

		// TODO make the "things" in _nutanix.gsp (morpheus-ui) appear here
		// crib off of getNodeOptionTypes() in morpheus-nutanix-prism-plugin/src/main/groovy/com/morpheusdata/nutanix/prism/plugin/NutanixPrismProvisionProvider.groovy
		// for the various field values
		nodeOptions << new OptionType(
			name: 'virtual image',
			code: 'nutanix-prism-element-node-virtualImageId-type',
			fieldCode: 'gomorpheus.label.vmImage',
			fieldContext: 'domain',
			fieldName: 'virtualImage.id',
			displayOrder: 10,
			inputType: OptionType.InputType.SELECT,
			optionSource: 'selectNutanixImage',
		)

		nodeOptions << new OptionType(
			name: 'log folder',
			code: 'nutanix-prism-element-node-logFolder-type',
			fieldCode: 'gomorpheus.label.logFolder',
			fieldContext: 'domain',
			fieldName: 'mountLogs',
			displayOrder: 20,
			inputType: OptionType.InputType.TEXT,
		)

		nodeOptions << new OptionType(
			name: 'config folder',
			code: 'nutanix-prism-element-node-configFolder-type',
			fieldCode: 'gomorpheus.label.configFolder',
			fieldContext: 'domain',
			fieldName: 'mountConfig',
			displayOrder: 30,
			inputType: OptionType.InputType.TEXT,
		)

		nodeOptions << new OptionType(
			name: 'deploy folder',
			code: 'nutanix-prism-element-node-deployFolder-type',
			fieldCode: 'gomorpheus.label.deployFolder',
			fieldContext: 'domain',
			fieldName: 'mountData',
			displayOrder: 40,
			inputType: OptionType.InputType.TEXT,
		)

		/* TODO the PE GSP has this hidden field. Does it go here or in the backup provider?
		nodeOptions << new OptionType(
			name: 'backup type',
			code: 'nutanix-prism-element-node-backup-type',
			fieldContext: '???',
			fieldName: 'backupType',
			defaultValue: 'nutanixSnapshot',
			displayOrder: 50,
			inputType: OptionType.InputType.HIDDEN,
		)
		*/
		nodeOptions << new OptionType(
			name: 'statTypeCode',
			code: 'nutanix-prism-element-node-stat-code-type',
			fieldContext: 'domain',
			fieldName: 'statTypeCode',
			defaultValue: 'vm',
			displayOrder: 60,
			inputType: OptionType.InputType.HIDDEN,
		)

		nodeOptions << new OptionType(
			name: 'logTypeCode',
			code: 'nutanix-prism-element-node-log-code-type',
			fieldContext: 'domain',
			fieldName: 'logTypeCode',
			defaultValue: 'vm',
			displayOrder: 70,
			inputType: OptionType.InputType.HIDDEN,
		)

		nodeOptions << new OptionType(
			name: 'showServerLogs',
			code: 'nutanix-prism-element-node-show-server-logs',
			fieldContext: 'domain',
			fieldName: 'showServerLogs',
			defaultValue: 'true',
			displayOrder: 80,
			inputType: OptionType.InputType.HIDDEN,
		)

		nodeOptions << new OptionType(
			name: 'serverType',
			code: 'nutanix-prism-element-node-server-type',
			fieldContext: 'domain',
			fieldName: 'serverType',
			defaultValue: 'vm',
			displayOrder: 90,
			inputType: OptionType.InputType.HIDDEN,
		)

		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		NutanixPrismElementStorageUtility.getDefaultStorageVolumes()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		NutanixPrismElementStorageUtility.getDefaultStorageVolumes()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		// TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
		return plans
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		// TODO: this is where you will implement the work to create the workload in your cloud environment
		return new ServiceResponse<ProvisionResponse>(
			true,
			null, // no message
			null, // no errors
			new ProvisionResponse(success:true)
		)
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
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
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return PROVISION_PROVIDER_NAME
	}

	@Override
	String getDefaultInstanceTypeDescription() {
		return 'Spin up any VM Image on your Nutanix Prism Element infrastructure.'
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		Collection<ComputeServerInterfaceType> ifaces = []

		ifaces << new ComputeServerInterfaceType(
				code: 'nutanix.virtio',
				externalId: 'NORMAL_NIC',
				name: 'Nutanix Prism Element VirtIO NIC',
				defaultType: true,
				enabled: true,
				displayOrder: 1
		)

		ifaces << new ComputeServerInterfaceType(
				code: 'nutanix.E1000',
				externalId: 'NORMAL_NIC',
				name: 'Nutanix Prism Element E1000 NIC',
				defaultType: false,
				enabled: true,
				displayOrder: 2
		)

		ifaces
	}

	@Override
	Collection<VirtualImageType> getVirtualImageTypes() {
		Collection<VirtualImageType> virtualImageTypes = []

		virtualImageTypes << new VirtualImageType(code: 'raw', name: 'RAW')
		virtualImageTypes << new VirtualImageType(code: 'qcow2', name: 'QCOW2')

		virtualImageTypes
	}
}
