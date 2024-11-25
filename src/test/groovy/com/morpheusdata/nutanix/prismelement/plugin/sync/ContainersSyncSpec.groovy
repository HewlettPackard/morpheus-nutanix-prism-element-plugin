package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusDatastoreService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Observable
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import static org.assertj.core.api.Assertions.*;

class ContainersSyncSpec extends Specification {
	@Subject
	ContainersSync service
	@Shared
	HttpApiClient client
	Cloud cloud
	MorpheusContext morpheusContext

	def setup() {
		morpheusContext = Mock(MorpheusContext)
		MorpheusAsyncServices asyncServices = Mock(MorpheusAsyncServices)
		MorpheusCloudService cloudService = Mock(MorpheusCloudService)
		MorpheusDatastoreService datastoreService = Mock(MorpheusDatastoreService)
		MorpheusServices syncServices = Mock(MorpheusServices)
		MorpheusSynchronousCloudService syncCloudService = Mock(MorpheusSynchronousCloudService)
		MorpheusSynchronousDatastoreService syncDatastoreService = Mock(MorpheusSynchronousDatastoreService)

		morpheusContext.getAsync() >> asyncServices
		asyncServices.getCloud() >> cloudService
		cloudService.getDatastore() >> datastoreService

		morpheusContext.getServices() >> syncServices
		syncServices.getCloud() >> syncCloudService
		syncCloudService.getDatastore() >> syncDatastoreService

		client = Mock(HttpApiClient)
		cloud = new Cloud(id: 1, owner: new Account())
		service = new ContainersSync(morpheusContext, cloud, client)
	}

	void "successful container sync with new container"() {
		given:
		def payload = new JsonSlurper().parse(this.getClass().getResourceAsStream("/fixtures/storage_containers.json"))
		def expectedDatastore = new Datastore(
			owner: cloud.owner,
			code: "nutanix.acropolis.datastore.${cloud.id}.000623fa-11d0-1ee5-7f35-5254004b8782::4",
			cloud: cloud,
			category: "nutanix.acropolis.datastore.${cloud.id}",
			name: "default-container-54726310217554",
			internalId: "000623fa-11d0-1ee5-7f35-5254004b8782::4",
			externalId: "2b877466-edbf-4363-8502-dfba188a4bcf",
			refType: 'ComputeZone',
			refId: cloud.id,
			storageSize: 692010703654,
			freeSpace: 688728992550,
			active: cloud.defaultDatastoreSyncActive
		)

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> new ServiceResponse(data: payload, success: true)
		1 * morpheusContext.async.cloud.datastore.listIdentityProjections(_) >> Observable.empty()
		1 * morpheusContext.services.cloud.datastore.bulkCreate(_) >> { List args ->
			assertThat(args[0])
				.usingRecursiveComparison()
				.isEqualTo([expectedDatastore])
		}
	}

	void "successful container sync with existing container"() {
		given:
		def payload = new JsonSlurper().parse(this.getClass().getResourceAsStream("/fixtures/storage_containers.json"))

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> new ServiceResponse(data: payload, success: true)

		1 * morpheusContext.async.cloud.datastore.listIdentityProjections(_) >> Observable.fromIterable(
			[
				[
					externalId: '2b877466-edbf-4363-8502-dfba188a4bcf',
				] as DatastoreIdentity
			]
		)

		1 * morpheusContext.async.cloud.datastore.listById(_) >> { List args ->
			def ids = args[0]
			assert ids.size() == 1
		}

		// Currently, no update is performed
	}

	void "successful container sync with missing container"() {
		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> new ServiceResponse(data: [], success: true)
		1 * morpheusContext.async.cloud.datastore.listIdentityProjections(_) >> Observable.fromIterable(
			[
				[
					externalId: '2b877466-edbf-4363-8502-dfba188a4bcf',
				] as DatastoreIdentity
			]
		)
		1 * morpheusContext.services.cloud.datastore.bulkRemove(_) >> { List args ->
			List<Datastore> newDatastores = args[0]
			newDatastores.size() == 1
		}
	}
}
