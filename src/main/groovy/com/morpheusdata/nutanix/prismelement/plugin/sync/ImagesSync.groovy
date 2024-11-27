package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPlugin
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j

/**
 * Synchronizes images from Nutanix to the internal VirtualImage and VirtualImageLocation models
 */
@Slf4j
class ImagesSync {
	private final MorpheusContext morpheusContext
	private final HttpApiClient client
	private final Cloud cloud
	// TODO: The API restricts it to either qcow2 or iso, and then we
	//       strip out the iso in the start of execute, so do we need an allow list?
	private final List<String> ALLOWED_IMAGE_TYPES = ['qcow2', 'disk', 'raw', 'iso']

	ImagesSync(MorpheusContext morpheusContext, Cloud cloud, HttpApiClient client) {
		this.morpheusContext = morpheusContext
		this.cloud = cloud
		this.client = client
	}

	def execute() {
		log.info("Executing image sync for cloud $cloud.name")

		try {
			def authConfig = NutanixPrismElementPlugin.getAuthConfig(morpheusContext, cloud)
			def listResults = NutanixPrismElementApiService.listImages(client, authConfig)
			if (listResults.success == true) {
				//ignore isos for now
				def cloudImages = listResults?.images?.findAll { it.imageType != 'iso' }

				def existingLocations = morpheusContext.async.virtualImage.location.list(
					new DataQuery()
						.withFilter(
							new DataAndFilter(
								new DataFilter('refType', 'ComputeZone'),
								new DataFilter('refId', cloud.id),
							)
						)
				)

				// Sync all the the locations of the images and then within that context we'll sync the images themselves.
				SyncTask<VirtualImageLocationIdentityProjection, Map, VirtualImageLocation> syncTask = new SyncTask<>(existingLocations, cloudImages)
				syncTask.addMatchFunction { VirtualImageLocationIdentityProjection imageLocationProjection, Map nutanixImage ->
					return nutanixImage.uuid == imageLocationProjection.externalId
						|| nutanixImage?.name == imageLocationProjection.imageName
						|| nutanixImage?.vmDiskId == imageLocationProjection.externalId
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, VirtualImageLocation>> updateItems ->
					morpheusContext.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd {
					log.debug("Adding missing image locations: $it")
					addMissingVirtualImageLocations(it)
				}.onUpdate {
					log.debug("Updating image locations: $it")
					updateVirtualImageLocations(it)
				}.onDelete {
					log.debug("Deleting image locations: $it")
					morpheusContext.services.virtualImage.location.bulkRemove(it)
				}.start()

				// If there wasn't a corresponding location for an image, remove that image
				removeSyncedImagesWithoutLocations()
			}
		} catch (e) {
			log.error("Image sync error for cloud $cloud.name: ${e}", e)
		}
	}

	private addMissingVirtualImageLocations(Collection<Map> addItems) {
		def existingRecords = morpheusContext.async.virtualImage.listIdentityProjections(
			new DataQuery()
				.withFilters(
					new DataFilter('imageType', 'in', ALLOWED_IMAGE_TYPES),
					new DataOrFilter(
						new DataFilter('owner', null),
						new DataFilter('owner.id', '==', cloud.owner.id)
					),
					new DataOrFilter(
						new DataFilter('externalId', 'in', (addItems.collect { it.uuid } + addItems.collect { it.vmDiskId }).unique()),
						new DataFilter('name', 'in', addItems.collect { it.name })
					)
				)
		)

		SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(existingRecords, addItems)
		syncTask.addMatchFunction { VirtualImageIdentityProjection existingItem, Map cloudItem ->
			cloudItem.uuid == existingItem.externalId
				|| cloudItem?.vmDiskId == existingItem.externalId
				|| cloudItem?.name == existingItem.name
		}.onDelete { removeItems ->
			// noop
		}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
			// If we didn't have a location for this cloud image but we do have an image, add the missing location
			log.debug("Updating images: $updateItems")
			addMissingVirtualImageLocationsForImages(updateItems)
		}.onAdd { itemsToAdd ->
			// If we don't have a location or an image for this cloud image, add both
			log.debug("adding images: $itemsToAdd")
			addMissingVirtualImages(itemsToAdd)
		}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, VirtualImage>> updateItems ->
			morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)
		}.start()
	}

	private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, Map>> addItems) {
		log.debug("addMissingVirtualImageLocationsForImages ${addItems?.size()}")

		def locationAdds = []
		addItems?.each { add ->
			def location = buildVirtualImageLocation(cloud, add.masterItem)
			location.virtualImage = add.existingItem
			locationAdds << location
		}

		if (locationAdds) {
			log.debug("About to create ${locationAdds.size()} locations")
			morpheusContext.services.virtualImage.location.create(locationAdds, cloud)
		}
	}

	void addMissingVirtualImages(Collection<Map> cloudImages) {
		def locations = []
		def images = cloudImages.collect {
			def image = buildVirtualImage(cloud, it)
			def location = buildVirtualImageLocation(cloud, it)
			location.virtualImage = image
			locations << location
			image
		}

		morpheusContext.services.virtualImage.bulkCreate(images)
		morpheusContext.services.virtualImage.location.bulkCreate(locations)
	}

	private static VirtualImage buildVirtualImage(Cloud cloud, Map cloudImage) {
		VirtualImage image = new VirtualImage(
			owner: cloud.owner,
			account: cloud.account,
			category: "nutanix.acropolis.image.${cloud.id}",
			name: cloudImage.name,
			code: "nutanix.acropolis.image.${cloud.id}.${cloudImage.uuid}",
			status: 'Active',
			imageType: cloudImage.imageType as ImageType,
			bucketId: cloudImage.containerId,
			uniqueId: cloudImage.uuid,
			externalId: cloudImage.vmDiskId,
			refType: 'ComputeZone',
			refId: cloud.id
		)
		image
	}

	private static VirtualImageLocation buildVirtualImageLocation(Cloud cloud, Map cloudImage) {
		return new VirtualImageLocation(
			owner: cloud.owner,
			code: "nutanix.acropolis.image.${cloud.id}.${cloudImage.uuid}",
			internalId: cloudImage.uuid,
			externalId: cloudImage.vmDiskId,
			externalDiskId: cloudImage.vmDiskId,
			imageRegion: cloud.regionCode,
			refType: 'ComputeZone',
			refId: cloud.id,
			imageName: cloudImage.name,
		)
	}

	void updateVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems) {
		log.debug "updateVirtualImageLocations: ${cloud} ${updateItems.size()}"
		List<VirtualImageLocation> saveLocationList = []
		List<VirtualImage> saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage
			.listById(updateItems.collect { it.existingItem.virtualImage.id })
			.toMap { it.id }.blockingGet()

		for (SyncTask.UpdateItem<VirtualImageLocation, Map> updateItem in updateItems) {
			def existingLocation = updateItem.existingItem
			def existingImage = virtualImagesById[existingLocation.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def saveLocation = false
			def saveImage = false

			def imageName = updateItem.masterItem.name
			if (existingLocation.imageName != imageName) {
				existingLocation.imageName = imageName

				if (existingImage.imageLocations?.size() < 2) {
					existingImage.name = imageName
					saveImage = true
				}
				saveLocation = true
			}

			if (existingLocation.externalId != cloudItem.vmDiskId) {
				existingLocation.externalId = cloudItem.vmDiskId
				saveLocation = true
			}

			if (existingLocation.imageRegion != cloud.regionCode) {
				existingLocation.imageRegion = cloud.regionCode
				saveLocation = true
			}

			if (saveLocation) {
				saveLocationList << existingLocation
			}

			if (saveImage) {
				saveImageList << existingImage
			}
		}

		if (saveLocationList) {
			morpheusContext.services.virtualImage.location.save(saveLocationList, cloud)
		}

		if (saveImageList) {
			morpheusContext.async.virtualImage.save(saveImageList.unique(), cloud).blockingGet()
		}
	}

	private removeSyncedImagesWithoutLocations() {
		def images = morpheusContext.services.virtualImage.list(new DataQuery().withFilters(
			new DataFilter("category", "nutanix.acropolis.image.${cloud.id}"),
			new DataFilter("userUploaded", false),
			new DataOrFilter(
				new DataFilter('systemImage', null),
				new DataFilter("systemImage", false),
			),
			new DataOrFilter(
				new DataFilter('owner', null),
				new DataFilter('owner.id', cloud.owner.id)
			)
		)).findAll { it.imageLocations.size() == 0 }
		if (images) {
			log.debug("Removing ${images.size()} images without locations")
			morpheusContext.services.virtualImage.bulkRemove(images)
		}
	}
}
