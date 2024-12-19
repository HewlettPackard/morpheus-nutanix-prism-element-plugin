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

package com.morpheusdata.nutanix.prismelement.plugin.provision

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.cloud.NutanixPrismElementCloudProvider
import com.morpheusdata.nutanix.prismelement.plugin.dataset.NutanixPrismElementVirtualImageDatasetProvider
import com.morpheusdata.nutanix.prismelement.plugin.utils.RequestConfig
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementComputeUtility
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementStorageUtility
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementSyncUtility
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import static com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementComputeUtility.saveAndGet

@Slf4j
class NutanixPrismElementProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, HostProvisionProvider.ResizeFacet, WorkloadProvisionProvider.ResizeFacet, ProvisionProvider.SnapshotFacet {
	public static final String PROVISION_PROVIDER_CODE = 'nutanix-prism-element-provision-provider'
	public static final String PROVISION_PROVIDER_NAME = 'Nutanix Prism Element'

	protected MorpheusContext morpheusContext
	protected Plugin plugin

	NutanixPrismElementProvisionProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super()
		this.@morpheusContext = morpheusContext
		this.@plugin = plugin
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "prepareWorkload: ${workload} ${workloadRequest} ${opts}"

		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload, options: [sendIp: false], disableCloudInit: false)
		try {
			Long virtualImageId = workload.getConfigProperty('imageId')?.toLong() ?: workload?.workloadType?.virtualImage?.id ?: opts?.config?.imageId
			if (!virtualImageId) {
				resp.msg = "No virtual image selected"
			} else {
				VirtualImage virtualImage
				try {
					virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)
				} catch (e) {
					log.error "error in get image: ${e}"
				}
				if (!virtualImage) {
					resp.msg = "No virtual image found for ${virtualImageId}"
				} else {
					workload.server.sourceImage = virtualImage
					saveAndGet(morpheusContext, workload.server)
					resp.success = true
				}
			}
		} catch (e) {
			resp.msg = "Error in PrepareWorkload: ${e}"
			log.error "${resp.msg}, ${e}", e
		}
		if (!resp.success) {
			log.error "prepareWorkload: error - ${resp.msg}"
		}
		return resp
	}

	@Override
	String getProvisionTypeCode() {
		return NutanixPrismElementCloudProvider.CLOUD_PROVIDER_CODE
		// cloud code and provision code match for existing plugin
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
			name: 'skip agent install',
			code: 'provisionType.general.noAgent',
			category: 'provisionType.amazon',
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
	 * {@inheritDoc}
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []

		nodeOptions << new OptionType([
			name : 'osType',
			code : 'nutanix-prism-element-node-os-type',
			fieldName : 'osType.id',
			fieldContext : 'domain',
			fieldLabel : 'OsType',
			inputType : OptionType.InputType.SELECT,
			displayOrder : 11,
			required : false,
			optionSource : 'osTypes'
		])

		nodeOptions << new OptionType(
			name: 'virtual image',
			code: 'nutanix-prism-element-node-virtualImageId-type',
			fieldCode: 'gomorpheus.label.vmImage',
			fieldContext: 'domain',
			fieldName: 'virtualImage.id',
			displayOrder: 10,
			inputType: OptionType.InputType.SELECT,
			optionSourceType: NutanixPrismElementVirtualImageDatasetProvider.PROVIDER_NAMESPACE,
			optionSource: NutanixPrismElementVirtualImageDatasetProvider.PROVIDER_KEY,
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

		nodeOptions << new OptionType(
			name: 'backup type',
			code: 'nutanix-prism-element-node-backup-type',
			fieldContext: 'instanceType',
			fieldName: 'backupType',
			defaultValue: 'nutanixSnapshot',
			displayOrder: 50,
			inputType: OptionType.InputType.HIDDEN,
		)

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

		nodeOptions << new OptionType(
			name: 'layout description',
			code: 'nutanix-prism-element-node-description-type',
			fieldContext: 'instanceTypeLayout',
			fieldName: 'description',
			defaultValue: 'This will provision a single vm container',
			displayOrder: 100,
			inputType: OptionType.InputType.HIDDEN,
		)

		return nodeOptions
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		NutanixPrismElementStorageUtility.getDefaultStorageVolumes()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		NutanixPrismElementStorageUtility.getDefaultStorageVolumes()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		// TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
		// TODOOOO
		return plans
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		def rtn = ServiceResponse.success()
		try {
			def validationOpts = opts
			validationOpts.networkId = (opts?.config?.nutanixNetworkId ?: opts?.nutanixNetworkId)
			def imageId = opts?.config?.imageId ?: opts?.imageId
			if (imageId) {
				validationOpts += [imageId: imageId]
			}
			def validationResults = NutanixPrismElementApiService.validateServerConfig(validationOpts)
			if (!validationResults.success) {
				rtn = ServiceResponse.error("Server config validation failed", validationResults.errors)
			}
		} catch (e) {
			log.error("validate container error: ${e}", e)
			rtn = ServiceResponse.error("validate container error: ${e.message}")
		}
		log.debug("validateContainer: ${rtn}")
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug("runWorkload: ${workload} ${workloadRequest} ${opts}")

		ProvisionResponse provisionResponse = new ProvisionResponse(success: false, noAgent: opts.noAgent)
		HttpApiClient client = new HttpApiClient()
		// use proxy from workload since this can be either the cloud or global proxy
		client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)

		try {
			def server = workload.server
			def cloud = workload.server.cloud
			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)

			server.setConfigProperty('osUsername', workload.getConfigProperty('nutanixUsr'))
			server.setConfigProperty('osPassword', workload.getConfigProperty('nutanixPwd'))
			server.setConfigProperty('publicKeyId', workload.getConfigProperty('publicKeyId'))
			server = saveAndGet(morpheusContext, server)

			Map createOpts = buildWorkloadCreateVmOpts(cloud, server, workload, workloadRequest, opts)

			// ensure image is uploaded
			def imageId = getOrUploadImage(client, reqConfig, cloud, workload.server.sourceImage, workload.instance.createdBy, createOpts.containerId)
			if (!imageId) {
				return ServiceResponse.error("No image file found for virtual image ${server.sourceImage?.id}:${server.sourceImage?.name}")
			}
			createOpts.imageId = imageId

			def result = runVm(client, reqConfig, server, null, workloadRequest, createOpts)
			provisionResponse.success = result.success
			if (result.createUsers) {
				provisionResponse.createUsers = result.createUsers
			}

			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error("runWorkload error: ${e}", e)
			return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
		} finally {
			client.shutdownClient()
		}
	}

	private static NetworkProxy buildNetworkProxy(ProxyConfiguration proxyConfiguration) {
		NetworkProxy networkProxy = new NetworkProxy()
		if (proxyConfiguration) {
			networkProxy.proxyDomain = proxyConfiguration.proxyDomain
			networkProxy.proxyHost = proxyConfiguration.proxyHost
			networkProxy.proxyPassword = proxyConfiguration.proxyPassword
			networkProxy.proxyUser = proxyConfiguration.proxyUser
			networkProxy.proxyPort = (Integer) proxyConfiguration.proxyPort
			networkProxy.proxyWorkstation = proxyConfiguration.proxyWorkstation
		}
		return networkProxy
	}

	private String getOrUploadImage(HttpApiClient client, RequestConfig reqConfig, Cloud cloud, VirtualImage virtualImage, User createdBy, String datastoreId) {
		def imageExternalId = null
		def lockId = null
		def location = null
		def lockKey = "nutanix.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()
		try {
			//hold up to a 1 hour lock for image upload
			lockId = morpheusContext.acquireLock(lockKey, [timeout: 60l * 60l * 1000l, ttl: 60l * 60l * 1000l]).blockingGet()

			// Check if it already exists
			if (virtualImage) {
				VirtualImageLocation virtualImageLocation
				try {
					virtualImageLocation = morpheusContext.services.virtualImage.location.find(
						new DataQuery()
							.withFilter('virtualImage.id', virtualImage.id)
							.withFilter('refType', 'ComputeZone')
							.withFilter('refId', cloud.id)
							.withFilter('imageRegion', cloud.regionCode)
					)

					imageExternalId = virtualImageLocation?.externalId
				} catch (e) {
					log.info("Error in findVirtualImageLocation.. could be not found ${e}", e)
				}

				// validate it exists in nutanix
				if (imageExternalId) {
					imageExternalId = NutanixPrismElementApiService.checkImageId(client, reqConfig, imageExternalId)
				}
			}

			// Either we don't have a location or it's not uploaded to nutanix.
			// Check if nutanix has the image already. If so, make a location for it
			if (!imageExternalId && virtualImage.systemImage || virtualImage.userUploaded) {
				def imageList = NutanixPrismElementApiService.listImages(client, reqConfig)
				if (imageList.success) {
					def existingImage = imageList.data.find { it.status?.name == virtualImage.name }
					if (existingImage) {
						imageExternalId = existingImage.metadata?.uuid
						VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
							virtualImage: virtualImage,
							externalId  : imageExternalId,
							imageRegion : cloud.regionCode,
							code        : "nutanix.prism.image.${cloud.id}.${imageExternalId}",
							internalId  : imageExternalId,
							refId       : cloud.id,
							refType     : 'ComputeZone'
						])
						location = morpheusContext.async.virtualImage.location.create(virtualImageLocation, cloud).blockingGet()
					}
				}
			}

			// If nutanix didn't have it either, let's do the full upload
			if (!imageExternalId) {
				// Create the image
				def cloudFiles = morpheusContext.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				def imageFile = cloudFiles?.find { cloudFile -> cloudFile.name.toLowerCase().endsWith(".qcow2") }
				// The url given will be used by Nutanix to download the image.. it will be in a RUNNING status until the download is complete
				// For morpheus images, this is fine as it is publicly accessible. But, for customer uploaded images, need to upload the bytes
				def copyUrl = morpheusContext.async.virtualImage.getCloudFileStreamUrl(virtualImage, imageFile, createdBy, cloud).blockingGet()
				def containerImage = [
					name         : virtualImage.name,
					imageSrc     : imageFile?.getURL(),
					minDisk      : virtualImage.minDisk ?: 5,
					minRam       : virtualImage.minRam ?: (512 * ComputeUtility.ONE_MEGABYTE),
					tags         : 'morpheus, ubuntu',
					imageType    : 'disk_image',
					containerType: 'qemu',
					cloudFiles   : cloudFiles,
					imageFile    : imageFile,
					imageUrl     : copyUrl]

				def imageResults = NutanixPrismElementApiService.insertContainerImage(client, reqConfig,
					[containerId: datastoreId,
					 image      : containerImage,
					])

				if (imageResults.success) {
					imageExternalId = imageResults.imageDiskId
					// Create the VirtualImageLocation before waiting for the upload
					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage : virtualImage,
						externalId   : imageExternalId,
						imageRegion  : cloud.regionCode,
						code         : "nutanix.acropolis.image.${cloud.id}.${imageExternalId}",
						internalId   : imageExternalId,
						refId        : cloud.id,
						refType      : 'ComputeZone',
						sharedStorage: true
					])
					location = morpheusContext.services.virtualImage.location.create(virtualImageLocation, cloud)
				} else {
					log.error("Error in creating the image: ${imageResults.msg}")
				}
			}
		} finally {
			if (lockId) {
				morpheusContext.releaseLock(lockKey, [lock: lockId]).blockingGet()
			}
		}
		return imageExternalId
	}

	private Map runVm(
		HttpApiClient client,
		RequestConfig reqConfig,
		ComputeServer server,
		HostRequest hostRequest,
		WorkloadRequest workloadRequest,
		Map<String, Object> createOpts
	) {
		def rtn = [success: false]

		// configure cloud init
		Map cloudFileResults = [success: true]
		if (server.sourceImage?.isCloudInit) {
			def insertIso = isCloudInitIso(createOpts)
			if (insertIso) {
				String applianceServerUrl = hostRequest?.cloudConfigOpts?.applianceUrl
					?: workloadRequest?.cloudConfigOpts?.applianceUrl
					?: null

				if (applianceServerUrl) {
					def cloudFileUrl = applianceServerUrl + (applianceServerUrl.endsWith('/') ? '' : '/') + 'api/cloud-config/' + server.apiKey
					def cloudFileDiskName = 'morpheus_' + server.id + '.iso'
					cloudFileResults = NutanixPrismElementApiService.insertContainerImage(
						client,
						reqConfig,
						[
							containerId: createOpts.containerId,
							image      : [
								name     : cloudFileDiskName,
								imageUrl : cloudFileUrl,
								imageType: 'iso_image',
							]
						])

					createOpts.cloudFileId = cloudFileResults.imageDiskId
				} else {
					log.warn("Error configuring cloud-init - no appliance url")
				}
			} else {
				createOpts.cloudConfig = hostRequest?.cloudConfigUser ?: workloadRequest?.cloudConfigUser
			}
		} else {
			rtn.createUsers = hostRequest?.usersConfiguration?.createUsers
				?: workloadRequest?.usersConfiguration?.createUsers
		}

		//create it
		if (cloudFileResults.success == true) {
			rtn.success = createAndStartVM(client, reqConfig, server, createOpts)

			// if we created a cloudinit iso as part of provisioning, clean it up
			if (cloudFileResults.imageId) {
				NutanixPrismElementApiService.deleteImage(client, reqConfig, cloudFileResults.imageId)
			}
		} else {
			log.warn("error on cloud config: ${cloudFileResults}")
			server.statusMessage = 'Failed to load cloud config'
			saveAndGet(morpheusContext, server)
		}

		if (cloudFileResults.imageId) {
			//ok - done - delete cloud disk
			NutanixPrismElementApiService.deleteImage(client, reqConfig, cloudFileResults.imageId)
		}

		return rtn
	}

	private Map buildWorkloadCreateVmOpts(Cloud cloud, ComputeServer server, Workload workload, WorkloadRequest workloadRequest, Map opts) {
		def rootVolume = workload.server.volumes?.find { it.rootVolume == true }
		def maxStorage = rootVolume.maxStorage ?: workload.maxStorage ?: workload.instance.plan.maxStorage
		def datastore = getDatastoreOption(cloud, server.account, rootVolume?.datastore, rootVolume?.datastoreOption, maxStorage)
		def datastoreId = datastore?.externalId
		def dataDisks = workload.server?.volumes?.findAll { it.rootVolume == false }?.sort { it.id }

		def maxMemory = workload.maxMemory ?: workload.instance.plan.maxMemory

		def coresPerSocket = workload.coresPerSocket ?: workload.instance.plan.coresPerSocket ?: 1
		def maxCores = workload.maxCores ?: workload.instance.plan.maxCores

		return buildBaseCreateVmOpts(cloud, server) + [
			containerId   : datastoreId,
			coresPerSocket: coresPerSocket,
			dataDisks     : dataDisks,
			maxCores      : maxCores,
			maxMemory     : maxMemory,
			maxStorage    : maxStorage,
			networkConfig : workloadRequest.networkConfiguration,
			rootVolume    : rootVolume,
			snapshotId : opts.snapshotId
		]
	}

	private static Map buildBaseCreateVmOpts(Cloud cloud, ComputeServer server) {
		return [
			account   : server.account,
			domainName: server.getExternalDomain(),
			externalId: server.externalId,
			hostname  : server.getExternalHostname(),
			name      : server.name,
			server    : server,
			uefi      : server.sourceImage?.uefi,
			uuid      : server.apiKey,
			zone      : cloud,
		]
	}

	def getDatastoreOption(Cloud cloud, Account account, DatastoreIdentity datastore, String datastoreOption, Long size) {
		def rtn = null
		if (datastore) {
			rtn = datastore
		} else if (datastoreOption == 'auto' || !datastoreOption) {
			def datastores = morpheusContext.services.cloud.datastore.list(
				new DataQuery().withFilters(
					new DataFilter('refType', 'ComputeZone'),
					new DataFilter('refId', cloud.id),
					new DataFilter('type', 'generic'),
					new DataFilter('online', true),
					new DataFilter('active', true),
					new DataFilter('freeSpace', '>', size),
					new DataOrFilter(
						new DataFilter('owner.id', account.id),
						new DataFilter('visibility', 'public')
					)
				).withSort("freeSpace", DataQuery.SortOrder.desc)
			)
			rtn = datastores.find { ds ->
				ds.externalId != null && ds.externalId != ""
			}
		}
		return rtn
	}

	static boolean isCloudInitIso(Map createOpts) {
		def rtn = false
		if (createOpts.platform == 'windows' && createOpts.isSysprep != true) {
			rtn = true
		} else if (createOpts.snapshotId) {
			rtn = true
		} else {
			//check for unmanaged non dhcp networks
			if (createOpts.networkConfig?.primaryInterface?.network?.type?.code != 'nutanixManagedVlan' &&
				createOpts.networkConfig?.primaryInterface?.doDhcp == false) {
				rtn = true
			} else {
				def badMatch = createOpts.networkConfig?.extraInterfaces?.find { it.network?.type?.code != 'nutanixManagedVlan' && it.doDhcp == false }
				if (badMatch) {
					rtn = true
				}
			}
		}
		return rtn
	}

	private boolean createAndStartVM(HttpApiClient client, RequestConfig reqConfig, ComputeServer server, Map createOpts) {
		log.debug("create server")

		def createResults
		if (createOpts.snapshotId) {
			//cloning off a snapshot
			createResults = NutanixPrismElementApiService.cloneServer(client, reqConfig, createOpts)
		} else {
			//creating off an image
			createResults = findOrCreateServer(client, reqConfig, server, createOpts)
		}

		if (!createResults.success || !createResults.results?.uuid) {
			log.error("failed to create server: ${createResults}")
			server.statusMessage = 'Failed to create server'
			saveAndGet(morpheusContext, server)
			return false
		}

		log.debug("create server: ${createResults}")
		server.externalId = createResults.results.uuid
		server.powerState = ComputeServer.PowerState.on
		server = saveAndGet(morpheusContext, server)

		def loadVmResults = NutanixPrismElementApiService.loadVirtualMachine(client, reqConfig, server.externalId)
		def startResults = NutanixPrismElementApiService.startVm(
			client,
			reqConfig,
			[timestamp: loadVmResults?.results?.vm_logical_timestamp],
			server.externalId
		)
		if (!startResults.success) {
			log.error("failed to start server: ${startResults}")
			server.statusMessage = 'Failed to start server'
			saveAndGet(morpheusContext, server)
			return false
		}

		log.debug("start: ${startResults.success}")
		def serverDetail = NutanixPrismElementApiService.checkServerReady(client, reqConfig, server.externalId)
		if (serverDetail.success == true) {
			return true
		} else {
			log.error("failed to load server details: ${serverDetail}")
			server.statusMessage = 'Failed to load server details'
			saveAndGet(morpheusContext, server)
			return false
		}
	}

	static def findOrCreateServer(HttpApiClient client, RequestConfig reqConfig, ComputeServer server, Map opts) {
		def rtn = [success: false]
		def found = false
		if (server.externalId) {
			def serverDetail = NutanixPrismElementApiService.loadVirtualMachine(client, reqConfig, server.externalId)
			if (serverDetail.success == true && serverDetail.results.power_state == 'on') {
				found = true
				rtn.success = true
				rtn.results = serverDetail.results
			}
		}

		if (found == true) {
			return rtn
		} else {
			return NutanixPrismElementApiService.createServer(client, reqConfig, opts)
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		def rtn = [success: true, msg: null]
		log.debug("finalizeWorkload: ${workload?.id}")
		HttpApiClient client = new HttpApiClient()
		client.networkProxy = workload.server.cloud.apiProxy

		try {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)

			if(server.sourceImage?.isCloudInit || (server.sourceImage?.isSysprep && !server.sourceImage?.isForceCustomization)) {
				def vmDisks = NutanixPrismElementApiService.getVirtualMachineDisks(client, reqConfig, server.externalId)?.disks
				vmDisks.each { vmDisk ->
					if(vmDisk.is_cdrom) {
						NutanixPrismElementApiService.ejectDisk(client, reqConfig, server.externalId, vmDisk.id)
					}
				}
			}

			def vmDisks = NutanixPrismElementApiService.getVirtualMachineDisks(client, reqConfig, server.externalId)?.disks
			updateVolumes(server, vmDisks)

			def vmNics = NutanixPrismElementApiService.getVirtualMachineNics(client, reqConfig, server.externalId)?.nics
			updateNics(server, vmNics)
		} catch (e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeWorkload: ${e}", e)
		} finally {
			client.shutdownClient()
		}
		return new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	def updateVolumes(ComputeServer server, List<Map> disks) {
		// update storage volumes with disk id
		// order in disks matches the order in server.volumes
		if (disks) {
			def volumes = server.volumes.sort { it.displayOrder }
			volumes.eachWithIndex { StorageVolume volume, index ->
				def vmDisk = disks.size() > index ? disks[index] : null
				volume.externalId = vmDisk?.disk_address.vmdisk_uuid
				volume.internalId = vmDisk?.disk_address.disk_label
				volume.setConfigProperty("address", volume.internalId)
				morpheusContext.services.storageVolume.save(volume)
			}
		}
	}

	def updateNics(ComputeServer server, List<Map> nics) {
		if (nics) {
			def networkInterfaces = server.interfaces
			def existingMacs = networkInterfaces?.collect { it.externalId }
			networkInterfaces.each { networkInterface ->
				if (networkInterface.externalId == null) {
					//find a free one
					nics?.each { nic ->
						def existingMatch = existingMacs.find { it == nic.mac_address }
						if (!existingMatch) {
							existingMacs << nic.mac_address
							networkInterface.externalId = nic.mac_address
							morpheusContext.services.computeServer.computeServerInterface.save(networkInterface)
						}
					}
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		HttpApiClient client = new HttpApiClient()
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, workload.server.cloud)
		try {
			return NutanixPrismElementComputeUtility.doStop(client, reqConfig, workload.server, workload.server.cloud, "stopWorkload")
		} finally {
			client.shutdownClient()
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		HttpApiClient client = new HttpApiClient()
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, workload.server.cloud)
		try {
			return NutanixPrismElementComputeUtility.doStart(client, reqConfig, workload.server, "startWorkload")
		} finally {
			client.shutdownClient()
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		ServiceResponse rtn = ServiceResponse.prepare()
		if (!workload.server?.externalId) {
			rtn.msg = 'vm not found'
			return rtn
		}
		def cloud = workload.server.cloud
		def vmOpts = [
			server       : workload.server,
			zone         : cloud,
			proxySettings: cloud.apiProxy,
			externalId   : workload.server.externalId
		]

		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
		HttpApiClient client = new HttpApiClient()
		try {
			def vmResults = NutanixPrismElementApiService.loadVirtualMachine(client, reqConfig, vmOpts.externalId)
			if (vmResults?.results?.vm_logical_timestamp)
				vmOpts.timestamp = vmResults?.results?.vm_logical_timestamp
			def stopResults = stopServer(workload.server)
			if (!stopResults.success) {
				return stopResults
			}

			if (!opts.keepBackups) {
				List<SnapshotIdentityProjection> snapshots = workload.server.snapshots
				snapshots?.each { snap ->
					NutanixPrismElementApiService.deleteSnapshot(client, reqConfig, snap.externalId)
				}
			}
			def removeResults = NutanixPrismElementApiService.deleteServer(client, reqConfig, vmOpts.externalId)
			log.debug("remove results: ${removeResults}")
			if (removeResults.success == true) {
				rtn.success = true
			} else {
				rtn.msg = 'Failed to remove container'
			}
		} catch (e) {
			log.error("removeWorkload error: ${e}", e)
			rtn.msg = e.message
		} finally {
			client.shutdownClient()
		}
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		ProvisionResponse rtn = new ProvisionResponse()
		def serverUuid = server.externalId
		if (server && server.uuid) {
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy

			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
			try {
				Map serverDetails = NutanixPrismElementApiService.checkServerReady(client, reqConfig, serverUuid)
				if (serverDetails.success && serverDetails.ipAddresses) {
					rtn.externalId = serverUuid
					rtn.success = serverDetails.success
					rtn.publicIp = serverDetails.ipAddresses[0]
					rtn.privateIp = serverDetails.ipAddresses[0]
					return ServiceResponse.success(rtn)

				} else {
					return ServiceResponse.error("Server not ready/does not exist")
				}
			} finally {
				client.shutdownClient()
			}
		} else {
			return ServiceResponse.error("Could not find server uuid")
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		HttpApiClient client = new HttpApiClient()
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, computeServer.cloud)
		try {
			return NutanixPrismElementComputeUtility.doStop(client, reqConfig, computeServer, "stopServer")
		} finally {
			client.shutdownClient()
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		HttpApiClient client = new HttpApiClient()
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, computeServer.cloud)
		try {
			return NutanixPrismElementComputeUtility.doStart(client, reqConfig, computeServer, "startServer")
		} finally {
			client.shutdownClient()
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@morpheusContext
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getName() {
		return PROVISION_PROVIDER_NAME
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getDefaultInstanceTypeDescription() {
		return 'Spin up any VM Image on your Nutanix Prism Element infrastructure.'
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * We already have an instance type defined in a scribe file, no need to create a default
	 */
	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		listComputeServerInterfaceTypes()
	}

	static Collection<ComputeServerInterfaceType> listComputeServerInterfaceTypes() {
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

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<VirtualImageType> getVirtualImageTypes() {
		Collection<VirtualImageType> virtualImageTypes = []

		virtualImageTypes << new VirtualImageType(code: 'raw', name: 'RAW')
		virtualImageTypes << new VirtualImageType(code: 'qcow2', name: 'QCOW2')

		virtualImageTypes
	}

	static ServiceResponse resizeCompute(MorpheusContext context, HttpApiClient client, ComputeServer server, ResizeRequest resizeRequest) {

		def reqConfig = NutanixPrismElementApiService.getRequestConfig(context, server.cloud)
		def resizeOpts = [
			coresPerSocket: resizeRequest.coresPerSocket,
			maxCores: resizeRequest.maxCores,
			maxMemory: resizeRequest.maxMemory,
			serverId: server.externalId,
		]
		def resizeResults = NutanixPrismElementApiService.updateServer(client, reqConfig, resizeOpts)
		if (resizeResults.success) {
			def computeServer = context.services.computeServer.get(server.id)
			computeServer.coresPerSocket = resizeRequest.coresPerSocket
			computeServer.maxCores = resizeRequest.maxCores
			computeServer.maxMemory = resizeRequest.maxMemory
			context.services.computeServer.save(computeServer)
			return ServiceResponse.success()
		}

		return ServiceResponse.error('resize failed')
	}

	ServiceResponse resizeDisks(HttpApiClient client, ComputeServer server, ResizeRequest resizeRequest) {
		ServiceResponse rtn = ServiceResponse.success()
		def cloud = server.cloud
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
		def vmId = server.externalId
		def vmDisks = NutanixPrismElementApiService.getVirtualMachineDisks(client, reqConfig, vmId)?.disks
		resizeRequest.volumesUpdate.each { it ->
			def existingVolume = it.existingModel
			Map updateProps = it.updateProps
			if(updateProps.maxStorage < existingVolume.maxStorage) {
				log.info("requested size {} bytes is less than existing size of {} bytes for disk {}, skipping",
					updateProps.maxStorage,
					existingVolume.maxStorage,
					existingVolume.externalId
				)
				return
			}

			def size = updateProps.size?.toInteger() * ComputeUtility.ONE_GIGABYTE
			def diskId = existingVolume.externalId
			def diskToResize = vmDisks.find{ it.disk_address.vmdisk_uuid == diskId }
			def resizeResults = NutanixPrismElementApiService.resizeDisk(client, reqConfig, vmId, diskToResize, size)
			if(resizeResults.success == true) {
				//get new disk ID
				vmDisks = NutanixPrismElementApiService.getVirtualMachineDisks(client, reqConfig, vmId)?.disks
				def resizedDisk = vmDisks.find{ it.disk_address.vmdisk_uuid == diskId }.disk_address
				existingVolume.externalId = resizedDisk.vmdisk_uuid
				existingVolume.maxStorage = updateProps.maxStorage as Long
				morpheusContext.services.storageVolume.save(existingVolume)
			} else {
				log.error "Error in resizing volume: ${resizeResults}"
				rtn.error = resizeResults.error ?: "Error in resizing volume"
			}
		}

		resizeRequest.volumesAdd.each {
			//new disk
			def addOpts = [:]
			addOpts.zone = cloud
			if (it.datastoreId) {
				addOpts.containerId = it.datastoreId
			} else {
				def dataStore = getDatastoreOption(cloud, server.account, null, null, it.size.toLong())
				addOpts.containerId = dataStore?.externalId
			}
			def sizeGb = it.size?.toInteger()
			def busType = morpheusContext.services.storageVolume.storageVolumeType.get(it?.storageType?.toLong())?.code?.replace('nutanix-','')
			def diskResults = NutanixPrismElementApiService.addDisk(client, reqConfig, addOpts, vmId, sizeGb, busType)
			log.debug("create disk success: ${diskResults.success}")
			if(!diskResults.success) {
				rtn.error = "Error in creating the: ${diskResults}"
				log.error rtn.error
				// save the error but continue to try to add the other disks
				return
			}
			// poll the disk list and cross reference it with the list before the operation was attempted to make sure
			// nutanix actually added the disk.
			Boolean found = false
			def existingDisks = vmDisks

			vmDisks = NutanixPrismElementApiService.getVirtualMachineDisks(client, reqConfig, vmId)?.disks
			vmDisks.each { vmDisk ->
				def existingDisk = existingDisks.find{it.disk_address.vmdisk_uuid == vmDisk.disk_address.vmdisk_uuid}
				if(!existingDisk) {
					it.deviceName = it.deviceName?: NutanixPrismElementSyncUtility.generateVolumeDeviceName(vmDisk)
					it.index = it.index?: vmDisk.disk_address.device_index
					found = true
				}
			}
			if (!found) {
				log.error("success adding disk but disk not found in nutanix list")
				return
			}
			def computeServer = morpheusContext.services.computeServer.get(server.id)
			def newVolume = NutanixPrismElementSyncUtility.buildStorageVolume(morpheusContext, computeServer.account, computeServer, it as Map)
			morpheusContext.services.storageVolume.create(newVolume)
			computeServer.volumes.add(newVolume)
			morpheusContext.services.computeServer.save(computeServer)
		}

		// Delete any removed volumes
		resizeRequest.volumesDelete.each { volume ->
			log.info("Deleting volume: ${volume}, id: ${volume.externalId}")
			def delDisk = vmDisks.find({ it.disk_address.vmdisk_uuid == volume.externalId })
			def deleteDiskResults = NutanixPrismElementApiService.deleteDisk(client, reqConfig, vmId, delDisk)
			log.info("Delete Disk Results: ${deleteDiskResults}")
			def storageVolume = morpheusContext.services.storageVolume.get(volume.id)
			def computeServer = morpheusContext.services.computeServer.get(server.id)
			computeServer.volumes.remove(storageVolume)
			morpheusContext.services.computeServer.save(computeServer)
			// TODO: switch back to bulkRemove once fixed
			morpheusContext.async.storageVolume.remove([storageVolume], computeServer, true).blockingGet()
		}

		// This operation is considered failed if any of the request's resizes failed.
		rtn.success = !rtn.error
		return rtn
	}

	ServiceResponse resizeNetworks(HttpApiClient client, ComputeServer server, ResizeRequest resizeRequest) {
		// existing code does nothing for network updates so we don't either
		if(!resizeRequest.interfacesAdd && !resizeRequest.interfacesDelete) {
			return ServiceResponse.success()
		}

		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, server.cloud)
		def vmId = server.externalId
		def existingNics = NutanixPrismElementApiService.getVirtualMachineNics(client, reqConfig, vmId)?.nics
		def computeServer = morpheusContext.services.computeServer.get(server.id)
		resizeRequest.interfacesAdd?.each { newInterfaceOpts ->
			log.info("adding network: ${newInterfaceOpts}")
			def targetNetwork = morpheusContext.services.network.get(newInterfaceOpts.network.id.toLong())
			if (!targetNetwork) {
				log.error("couldn't find network id: ${newInterfaceOpts.network.id}. skipping...")
				return
			}

			def networkConfig = [
				networkUuid:targetNetwork.uniqueId,
				ipAddress:newInterfaceOpts.ipAddress
			]
			def networkResults = NutanixPrismElementApiService.addNic(client, reqConfig, networkConfig, server.externalId)
			log.info("network results: ${networkResults}")
			if(networkResults.success == true) {
				def newInterface = NutanixPrismElementSyncUtility.buildComputeServerInterface(morpheusContext, computeServer, newInterfaceOpts.network)
				def vmNics = NutanixPrismElementApiService.getVirtualMachineNics(client, reqConfig, server.externalId)?.nics
				vmNics.each { vmNic ->
					def existingNic = existingNics.find{it.mac_address == vmNic.mac_address}
					if(!existingNic) {
						newInterface.externalId = vmNic.mac_address
						newInterface.publicIpAddress = vmNic.requested_ip_address
					}
				}
				newInterface.uniqueId = "morpheus-nic-${computeServer.id}-${computeServer.interfaces.size()}"
				morpheusContext.services.computeServer.computeServerInterface.create(newInterface)
				computeServer.interfaces.add(newInterface)
			}
		}
		resizeRequest.interfacesDelete?.eachWithIndex { networkDelete, index ->
			def deleteConfig = [macAddress: networkDelete.macAddress]
			def deleteResults = NutanixPrismElementApiService.deleteNic(client, reqConfig, deleteConfig, server.externalId, networkDelete.externalId)
			log.debug("deleteResults: ${deleteResults}")
			if(deleteResults.success == true) {
				def networkInterface = morpheusContext.services.computeServer.computeServerInterface.get(networkDelete.id)
				computeServer.interfaces.remove(networkInterface)
				// TODO: switch back to bulkRemove once fixed
				morpheusContext.async.computeServer.computeServerInterface.remove([networkDelete], computeServer).blockingGet()
			}
		}

		morpheusContext.services.computeServer.save(computeServer)
		// this function only returns success since the embedded version didn't fail the resize if something
		// went wrong the the network stuff
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeServer: ${server}, ${resizeRequest}, ${opts}"

		return resizeInternal(server, resizeRequest)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeWorkload: ${instance}, ${resizeRequest}, ${opts}"

		return resizeInternal(workload.server, resizeRequest)
	}

	private ServiceResponse resizeInternal(ComputeServer server, ResizeRequest resizeRequest) {
		ServiceResponse rtn = ServiceResponse.prepare()
		HttpApiClient client = new HttpApiClient()
		try {
			server = morpheusContext.services.computeServer.get(server.id)
			rtn = resizeCompute(morpheusContext, client, server, resizeRequest)
			if (!rtn.success) {
				return rtn
			}

			rtn = resizeDisks(client, server, resizeRequest)
			if (!rtn.success) {
				return rtn
			}

			rtn = resizeNetworks(client, server, resizeRequest)
			if (!rtn.success) {
				return rtn
			}

			rtn.success = true
		} catch(Exception e){
			log.error("resizeResults error: ${e}", e)
			rtn.error = rtn.msg ?: e.message
			rtn.msg = e.message
		} finally {
			client.shutdownClient()
		}

		return rtn
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
	Boolean canCustomizeRootVolume() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean disableRootDatastore() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean canAddVolumes() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.info("validateServiceConfiguration: {}", opts)
		ServiceResponse resp = ServiceResponse.prepare()
		try {
			def validationOpts = opts
			validationOpts.networkId = (opts?.config?.nutanixNetworkId ?: opts?.nutanixNetworkId)
			if (opts?.config?.templateTypeSelect == 'custom')
				validationOpts += [imageId: opts?.config?.imageId]
			if (opts?.config?.containsKey('nodeCount')) {
				validationOpts += [nodeCount: opts.config.nodeCount]
			}
			def rtn = NutanixPrismElementApiService.validateServerConfig(validationOpts)
			if (!rtn.success) {
				resp.errors = rtn.errors
			}
			resp.success = rtn.success
		} catch (e) {
			log.error("error in validateServerConfig: ${e}", e)
		}
		return resp
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: true])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)
		if (server.sourceImage) {
			rtn.success = true
			return rtn
		}

		try {
			VirtualImage virtualImage = null
			Long computeTypeSetId = server.typeSet?.id
			def config = server.getConfigMap()
			def imageType = config.templateTypeSelect ?: 'default'

			if (computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
				if (computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
					virtualImage = workloadType.virtualImage
				}
			} else if (imageType == 'custom' && config.imageId) {
				Long virtualImageId = config.imageId?.toLong()
				if (virtualImageId) {
					virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)
				}
			} else {
				// TODO: this is the fallback... should we really do this?
				virtualImage = morpheusContext.services.virtualImage.find(new DataQuery().withFilter('code', 'nutanix.image.morpheus.ubuntu.16.04'))
			}

			if (!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(morpheusContext, server)
				rtn.success = true
			}
		} catch (e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		HttpApiClient client = new HttpApiClient()
		// use proxy from host request since this can be either the cloud or global proxy
		client.networkProxy = buildNetworkProxy(hostRequest.proxyConfiguration)

		ProvisionResponse provisionResponse = new ProvisionResponse(success: false, noAgent: opts.noAgent)
		try {
			def createOpts = buildHostCreateVmOpts(server.cloud, server, hostRequest)

			// ensure image is uploaded
			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, server.cloud)
			def imageId = getOrUploadImage(client, reqConfig, server.cloud, server.sourceImage, server.createdBy, createOpts.containerId)
			if (!imageId) {
				return ServiceResponse.error("No image file found for virtual image ${server.sourceImage?.id}:${server.sourceImage?.name}")
			}
			createOpts.imageId = imageId

			def result = runVm(client, reqConfig, server, hostRequest, null, createOpts)
			provisionResponse.success = result.success
			if (result.createUsers) {
				provisionResponse.createUsers = result.createUsers
			}

			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error("runHost error: ${e}", e)
			server.statusMessage = "Failed to create server: ${e.message}"
			return new ServiceResponse(success: false, msg: provisionResponse.message ?: '', error: provisionResponse.message, data: provisionResponse)

		} finally {
			client.shutdownClient()
		}
	}

	private Map buildHostCreateVmOpts(Cloud cloud, ComputeServer server, HostRequest hostRequest) {
		def rootVolume = server.volumes?.find { it.rootVolume == true }
		def maxStorage = getServerRootSize(server)
		def datastore = getDatastoreOption(cloud, server.account, rootVolume?.datastore, rootVolume?.datastoreOption, maxStorage)
		def datastoreId = datastore?.externalId
		def dataDisks = server?.volumes?.findAll { it.rootVolume == false }?.sort { it.id }

		def maxMemory = server.maxMemory ?: server.plan.maxMemory

		def coresPerSocket = server.coresPerSocket ?: server.plan.coresPerSocket ?: 1
		def maxCores = server.maxCores ?: server.plan.maxCores ?: 1

		return buildBaseCreateVmOpts(cloud, server) + [
			containerId   : datastoreId,
			coresPerSocket: coresPerSocket,
			dataDisks     : dataDisks,
			maxCores      : maxCores,
			maxMemory     : maxMemory,
			maxStorage    : maxStorage,
			networkConfig : hostRequest.networkConfiguration,
			rootVolume    : rootVolume,
		]
	}

	static Long getServerRootSize(ComputeServer server) {
		Long rtn
		StorageVolume rootDisk = server?.volumes?.find { StorageVolume it -> it.rootVolume == true }
		if (rootDisk)
			rtn = rootDisk.maxStorage
		else
			rtn = server.maxStorage ?: server.plan?.maxStorage
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server) {
		return getServerDetails(server)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse createSnapshot(ComputeServer server, Map opts) {
		log.debug("Creating snapshot of server {}", server.id)

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = server.cloud.apiProxy
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, server.cloud)
		try {
			def snapshotName = opts.snapshotName ?: "${server.name}.${System.currentTimeMillis()}"
			def snapshotOpts = [
				vmId: server.externalId,
				snapshotName: snapshotName
			]

			def snapshotResults = NutanixPrismElementApiService.createSnapshot(client, reqConfig, snapshotOpts)
			def taskId = snapshotResults?.results?.taskUuid
			if (!snapshotResults.success || !taskId) {
				return ServiceResponse.error("Failed to create snapshot", null, snapshotResults)
			}

			def taskResults = NutanixPrismElementApiService.checkTaskReady(client, reqConfig, taskId)
			if (!taskResults.success) {
				return ServiceResponse.error("Error waiting for create snapshot task to complete")
			}

			def snapId = taskResults?.results?.entity_list?.find { it.entity_type == 'snapshot'}?.entity_id
			if(snapId) {
				Snapshot savedSnapshot = morpheusContext.services.snapshot.create(new Snapshot(
					account        : server.account ?: server.cloud.owner,
					cloud          : server.cloud,
					name: snapshotName,
					snapshotCreated: new Date(),
					currentlyActive: true,
					externalId: snapId,
					description: opts.description
				))
				if (!savedSnapshot) {
					return ServiceResponse.error("Error saving snapshot")
				} else {
					morpheusContext.async.snapshot.addSnapshot(savedSnapshot, server).blockingGet()
				}
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error("Error fetching snapshot after creation", null, taskResults)
			}
		} catch (e) {
			log.error("Create snapshot: ${e}", e)
			return ServiceResponse.error(e.getMessage())
		}finally {
			client.shutdownClient()
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteSnapshots(ComputeServer server, Map opts) {
		def snapshots = morpheusContext.services.snapshot.listById(server.snapshots.collect { it.id })
		for (final def snapshot in snapshots) {
			def resp = deleteSnapshot(snapshot, opts)
			if (!resp.success) {
				return resp
			}
		}

		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteSnapshot(Snapshot snapshot, Map opts) {
		log.debug("Deleting snapshot {}", snapshot)

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = snapshot.cloud.apiProxy

		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, snapshot.cloud)
		try {
			def resp = NutanixPrismElementApiService.deleteSnapshot(client, reqConfig, snapshot.externalId)
			if (!resp.success) {
				return ServiceResponse.error("Failed to delete snapshot: ${resp.error}")
			}

		} finally {
			client.shutdownClient()
		}

		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse revertSnapshot(ComputeServer server, Snapshot snapshot, Map opts) {
		log.debug("Reverting snapshot {}", snapshot)
		def rtn = ServiceResponse.prepare()

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = server.cloud.apiProxy
		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, server.cloud)

		try {
			def resp = NutanixPrismElementApiService.restoreSnapshot(client, reqConfig, [vmId: server.externalId, snapshotId: snapshot.externalId])
			if (!resp.success) {
				return ServiceResponse.error(resp.msg as String)
			}

			resp = NutanixPrismElementApiService.checkTaskReady(client, reqConfig, resp.results?.task_uuid)
			if (!resp.success) {
				return ServiceResponse.error("Error waiting for revert snapshot task to complete")
			}

			if (resp.error) {
				return ServiceResponse.error("Failed to revert snapshot: ${resp.error}")
			}

			rtn.success = true
		} finally {
			client.shutdownClient()
		}

		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean hasCloneTemplate() {
		return true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse cloneToTemplate(Workload workload, Map opts) {
		def rtn = ServiceResponse.prepare()

		def server = workload.server

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = server.cloud.apiProxy

		def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, server.cloud)
		try {

			def snapshotName = "${workload.instance.name}.${workload.id}.${System.currentTimeMillis()}".toString()
			if (server.sourceImage?.isCloudInit && server.serverOs?.platform != PlatformType.windows) {
				morpheusContext.executeCommandOnServer(server,
					'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old ; sync',
					false, server.sshUsername, server.sshPassword, null, null, null, null, true, true)
					.blockingGet()
			}

			def snapshotOpts = [
				vmId        : server.externalId,
				snapshotName: snapshotName
			]
			def snapshotResults = NutanixPrismElementApiService.createSnapshot(client, reqConfig, snapshotOpts)
			if (!snapshotResults.success) {
				rtn.msg = 'clone failed'
				return rtn
			}

			def taskResults = NutanixPrismElementApiService.checkTaskReady(client, reqConfig, snapshotResults.results.taskUuid)
			if (!taskResults.success || taskResults.error) {
				rtn.msg = 'clone failed'
				return rtn
			}

			//clone it
			def snapshotEntity = taskResults.results.entity_list.find { it.entity_type == 'Snapshot' }
			def cloneConfig = [
				snapshotId: snapshotEntity?.entity_id,
				name: opts.templateName,
				containerId: server.cloud.getConfigProperty('imageStoreId')
			]
			def cloneResults = NutanixPrismElementApiService.cloneVmToImage(client, reqConfig, cloneConfig)
			log.debug("cloneResults: ${cloneResults}")
			if (!cloneResults.success) {
				rtn.msg = 'clone failed'
				return rtn
			}

			def cloneTaskResults = NutanixPrismElementApiService.checkTaskReady(client, reqConfig, cloneResults.taskUuid)
			log.debug("cloneTaskResults: ${cloneTaskResults}")
			if (!cloneTaskResults.success || cloneTaskResults.error) {
				rtn.msg = 'clone failed'
				return rtn
			}

			//get the image id - create the image
			def imageId = cloneTaskResults.results.entity_list[0].entity_id
			def imageResults = NutanixPrismElementApiService.loadImage(client, reqConfig, imageId)
			log.debug("imageResults: ${imageResults}")
			if (imageResults.success && imageResults?.image) {
				def vmDiskId = imageResults.image.vm_disk_id

				VirtualImage imageToSave = new VirtualImage(
					owner      : workload.account,
					category   : "nutanix.acropolis.image.${server.cloud.id}",
					name       : opts.templateName,
					code       : "nutanix.acropolis.image.${server.cloud.id}.${imageId}",
					status     : 'Active',
					imageType  : 'qcow2',
					bucketId   : (cloneResults.containerId ?: server.cloud.getConfigProperty('imageStoreId')),
					uniqueId   : imageId,
					externalId : vmDiskId,
					refType    : 'ComputeZone',
					refId      : "${server.cloud.id}",
					platform   : server.computeServerType.platform,
					imageRegion: server.cloud.regionCode,
				)
				def sourceImage = server.sourceImage
				if (sourceImage) {
					imageToSave.isCloudInit = sourceImage.isCloudInit
					imageToSave.isForceCustomization = sourceImage.isForceCustomization
					if (!imageToSave.osType && sourceImage.osType) {
						imageToSave.osType = sourceImage.osType
					}
					if (!imageToSave.platform && sourceImage.platform) {
						imageToSave.platform = sourceImage.platform
					}
				}

				def addLocation = new VirtualImageLocation(
					code          : "nutanix.acropolis.image.${server.cloud.id}.${imageId}",
					externalDiskId: vmDiskId,
					externalId    : vmDiskId,
					imageName     : opts.templateName,
					imageRegion: server.cloud.regionCode,
					internalId: imageId,
					refId         : server.cloud.id,
					refType       : 'ComputeZone',
					owner: server.cloud.owner,
				)
				imageToSave.imageLocations = [addLocation]
				morpheusContext.async.virtualImage.create(imageToSave, server.cloud).blockingGet()
			}
			//remove the snapshot
			def removeResult = NutanixPrismElementApiService.deleteSnapshot(client, reqConfig, snapshotEntity.entity_id)
			if (!removeResult.success) {
				log.error("Failed to remove snapshot: ${removeResult}")
			}
			rtn.success = true
		} catch (e) {
			log.error("cloneToTemplate error: ${e}", e)
			rtn.msg = e.message
		} finally {
			if (server.sourceImage?.isCloudInit && server.serverOs?.platform != PlatformType.windows) {
				morpheusContext.executeCommandOnServer(server,
					"sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync",
					false, server.sshUsername, server.sshPassword, null, null, null, null, true, true)
					.blockingGet()
			}
		}
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	HostType getHostType() {
		return HostType.vm
	}
}
