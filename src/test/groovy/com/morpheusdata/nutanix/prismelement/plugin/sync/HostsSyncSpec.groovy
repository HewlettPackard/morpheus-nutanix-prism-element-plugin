package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.*
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.compute.MorpheusComputeServerAccessService
import com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerAccessService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.cloud.sync.HostsSync
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

import static org.assertj.core.api.Assertions.assertThat

class HostsSyncSpec extends Specification {
	@Subject
	HostsSync service
	@Shared
	HttpApiClient client
	Cloud cloud
	MorpheusContext morpheusContext

	def setup() {
		morpheusContext = Mock(MorpheusContext)
		MorpheusAsyncServices asyncServices = Mock(MorpheusAsyncServices)
		MorpheusComputeServerService computeServerService = Mock(MorpheusComputeServerService)
		MorpheusComputeServerAccessService computeServerAccessService = Mock(MorpheusComputeServerAccessService)
		MorpheusCloudService cloudService = Mock(MorpheusCloudService)

		MorpheusServices syncServices = Mock(MorpheusServices)
		MorpheusSynchronousComputeServerService syncComputeServerService = Mock(MorpheusSynchronousComputeServerService)
		MorpheusSynchronousComputeServerAccessService synchronousComputeServerAccessService = Mock(MorpheusSynchronousComputeServerAccessService)
		MorpheusSynchronousCloudService syncCloudService = Mock(MorpheusSynchronousCloudService)
		MorpheusSynchronousOsTypeService syncOsTypeService = Mock(MorpheusSynchronousOsTypeService)

		morpheusContext.getAsync() >> asyncServices
		asyncServices.getCloud() >> cloudService
		asyncServices.getComputeServer() >> computeServerService
		computeServerService.getAccess() >> computeServerAccessService

		morpheusContext.getServices() >> syncServices
		syncServices.getComputeServer() >> syncComputeServerService
		syncComputeServerService.getAccess() >> synchronousComputeServerAccessService
		syncServices.getCloud() >> syncCloudService
		syncServices.getOsType() >> syncOsTypeService

		client = Mock(HttpApiClient)
		cloud = new Cloud(id: 1, owner: new Account())
		service = new HostsSync(morpheusContext, cloud, client)
	}

	void "successful host sync with new host"() {
		given:
		def payload = new JsonSlurper().parse(this.getClass().getResourceAsStream("/fixtures/hosts.json"))
		def expectedServerType = new ComputeServerType(code: "nutanixMetalHypervisor")
		def expectedOsType = new OsType(code: "linux")
		def expectedAccess = new ComputeServerAccess(
			accessType: 'ipmi',
			host: '192.168.0.1'
		)
		def expectedServer = new ComputeServer(
			account: cloud.owner,
			category: "nutanix.host.${cloud.id}",
			name: "NTNX-7925c5f8-A",
			externalId: "82d60458-c20b-431d-93ba-eed97aa5d641",
			cloud: cloud,
			sshUsername: 'root',
			apiKey: UUID.randomUUID(),
			status: 'provisioned',
			provision: false,
			singleTenant: false,
			serverType: 'hypervisor',
			computeServerType: expectedServerType,
			statusDate: new Date(),
			serverOs: expectedOsType,
			osType: 'linux',
			hostname: "NTNX-7925c5f8-A",
			sshHost: "10.114.164.252",
			externalIp: "10.114.164.252",
			internalIp: "10.114.164.252",
			powerState: ComputeServer.PowerState.on,
			maxMemory: 65581088768,
			maxCores: 144,
			maxStorage: 0,
			capacityInfo:  new ComputeCapacityInfo(
				maxMemory: 65581088768,
				maxCores: 144,
				maxStorage: 0
			),
			accesses: [
				expectedAccess
			]
		)

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> { args ->
			assertThat(args[1] as String).isEqualTo("/api/nutanix/v2.0/hosts")
			new ServiceResponse(data: payload, success: true)
		}

		1 * morpheusContext.async.computeServer.listIdentityProjections(_) >> { Observable.empty() }
		1 * morpheusContext.async.cloud.findComputeServerTypeByCode(_) >> Maybe.just(expectedServerType)
		1 * morpheusContext.services.osType.find(_) >> expectedOsType
		1 * morpheusContext.async.computeServer.access.create(_) >> { List args ->
			assertThat(args[0])
				.usingRecursiveComparison()
				.isEqualTo(expectedAccess)
			return Single.just(expectedAccess)
		}
		1 * morpheusContext.services.computeServer.bulkCreate(_) >> { List args ->
			assertThat(args[0])
				.usingRecursiveComparison()
				.ignoringFields(
					'dirtyProperties',
					'statusDate', // generated inline so it won't match
					'apiKey' // randomly generated
				)
				.isEqualTo([expectedServer])
			return new BulkCreateResult(null, null, [expectedServer], [])
		}
	}

	void "successful host sync with existing host, with changes should save"() {
		given:
		def payload = new JsonSlurper().parse(this.getClass().getResourceAsStream("/fixtures/hosts.json"))
		def expectedServerType = new ComputeServerType(code: "nutanixMetalHypervisor")
		def expectedOsType = new OsType(code: "linux")
		def expectedAccess = new ComputeServerAccess(
			accessType: 'ipmi',
			host: '192.168.0.1'
		)
		def expectedServer = new ComputeServer(
			id: 1,
			account: cloud.owner,
			category: "nutanix.host.${cloud.id}",
			name: "NTNX-7925c5f8-A",
			externalId: "82d60458-c20b-431d-93ba-eed97aa5d641",
			cloud: cloud,
			sshUsername: 'root',
			apiKey: UUID.randomUUID(),
			status: 'provisioned',
			provision: false,
			singleTenant: false,
			serverType: 'hypervisor',
			computeServerType: expectedServerType,
			statusDate: new Date(),
			serverOs: expectedOsType,
			osType: 'linux',
			hostname: "NTNX-7925c5f8-A",
			sshHost: "10.114.164.252",
			externalIp: "10.114.164.252",
			internalIp: "10.114.164.252",
			powerState: ComputeServer.PowerState.on,
			maxMemory: 1337,
			maxCores: 1337, // different
			maxStorage: 0,
			capacityInfo:  new ComputeCapacityInfo(
				maxMemory: 65581088768,
				maxCores: 144,
				maxStorage: 0
			),
			accesses: [
				expectedAccess
			]
		)

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> { args ->
			assertThat(args[1] as String).isEqualTo("/api/nutanix/v2.0/hosts")
			new ServiceResponse(data: payload, success: true)
		}
		1 * morpheusContext.async.cloud.findComputeServerTypeByCode(_) >> Maybe.just(expectedServerType)
		1 * morpheusContext.services.osType.find(_) >> expectedOsType

		1 * morpheusContext.async.computeServer.listIdentityProjections(_) >> {
			Observable.just(new ComputeServerIdentityProjection(id: 1, externalId: "82d60458-c20b-431d-93ba-eed97aa5d641"))
		}
		1 * morpheusContext.async.computeServer.listById(_) >> {
			Observable.just(expectedServer)
		}
		1 * morpheusContext.services.computeServer.bulkSave(_) >> { List args ->
			assertThat(args[0])
				.usingRecursiveComparison()
				.ignoringFields(
					'dirtyProperties',
					'statusDate', // generated inline so it won't match
					'apiKey' // randomly generated
				)
				.isEqualTo([expectedServer])
			return new BulkSaveResult(null, null, [expectedServer], [])
		}
	}

	void "successful host sync with existing host, no changes then no save"() {
		given:
		def payload = new JsonSlurper().parse(this.getClass().getResourceAsStream("/fixtures/hosts.json"))
		def expectedServerType = new ComputeServerType(code: "nutanixMetalHypervisor")
		def expectedOsType = new OsType(code: "linux")
		def expectedAccess = new ComputeServerAccess(
			accessType: 'ipmi',
			host: '192.168.0.1'
		)
		def expectedServer = new ComputeServer(
			id: 1,
			account: cloud.owner,
			category: "nutanix.host.${cloud.id}",
			name: "NTNX-7925c5f8-A",
			externalId: "82d60458-c20b-431d-93ba-eed97aa5d641",
			cloud: cloud,
			sshUsername: 'root',
			apiKey: UUID.randomUUID(),
			status: 'provisioned',
			provision: false,
			singleTenant: false,
			serverType: 'hypervisor',
			computeServerType: expectedServerType,
			statusDate: new Date(),
			serverOs: expectedOsType,
			osType: 'linux',
			hostname: "NTNX-7925c5f8-A",
			sshHost: "10.114.164.252",
			externalIp: "10.114.164.252",
			internalIp: "10.114.164.252",
			powerState: ComputeServer.PowerState.on,
			maxMemory: 65581088768,
			maxCores: 144,
			maxStorage: 0,
			capacityInfo:  new ComputeCapacityInfo(
				maxMemory: 65581088768,
				maxCores: 144,
				maxStorage: 0
			),
			accesses: [
				expectedAccess
			]
		)

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> { args ->
			assertThat(args[1] as String).isEqualTo("/api/nutanix/v2.0/hosts")
			new ServiceResponse(data: payload, success: true)
		}

		1 * morpheusContext.async.cloud.findComputeServerTypeByCode(_) >> Maybe.just(expectedServerType)
		1 * morpheusContext.services.osType.find(_) >> expectedOsType

		1 * morpheusContext.async.computeServer.listIdentityProjections(_) >> {
			Observable.just(new ComputeServerIdentityProjection(id: 1, externalId: "82d60458-c20b-431d-93ba-eed97aa5d641"))
		}
		1 * morpheusContext.async.computeServer.listById(_) >> {
			Observable.just(expectedServer)
		}
	}

	void "successful host sync with missing host"() {
		given:
		def expectedServerType = new ComputeServerType(code: "nutanixMetalHypervisor")
		def expectedOsType = new OsType(code: "linux")

		when:
		service.execute()

		then:
		1 * client.callJsonApi(*_) >> new ServiceResponse(data: [], success: true)
		1 * morpheusContext.async.cloud.findComputeServerTypeByCode(_) >> Maybe.just(expectedServerType)
		1 * morpheusContext.services.osType.find(_) >> expectedOsType
		1 * morpheusContext.async.computeServer.listIdentityProjections(_) >> {
			Observable.just(new ComputeServerIdentityProjection(externalId: "82d60458-c20b-431d-93ba-eed97aa5d641"))
		}
		1 * morpheusContext.async.computeServer.remove(_) >> {
			Single.just(new ComputeServer(externalId: "82d60458-c20b-431d-93ba-eed97aa5d641"))
		}
	}
}
