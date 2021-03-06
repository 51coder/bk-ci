package com.tencent.devops.dispatch.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.pojo.Zone
import com.tencent.devops.common.api.util.ApiUtil
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.DockerVersion
import com.tencent.devops.common.pipeline.type.docker.DockerDispatchType
import com.tencent.devops.common.pipeline.type.docker.ImageType
import com.tencent.devops.dispatch.dao.PipelineDockerBuildDao
import com.tencent.devops.dispatch.exception.DockerServiceException
import com.tencent.devops.dispatch.pojo.DockerHostBuildInfo
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.redis.RedisBuild
import com.tencent.devops.dispatch.utils.CommonUtils
import com.tencent.devops.dispatch.utils.DockerHostUtils
import com.tencent.devops.dispatch.utils.redis.RedisUtils
import com.tencent.devops.process.pojo.mq.PipelineAgentShutdownEvent
import com.tencent.devops.process.pojo.mq.PipelineAgentStartupEvent
import com.tencent.devops.ticket.pojo.enums.CredentialType
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DockerHostClient @Autowired constructor(
    private val pipelineDockerBuildDao: PipelineDockerBuildDao,
    private val dockerHostUtils: DockerHostUtils,
    private val redisUtils: RedisUtils,
    private val client: Client,
    private val dslContext: DSLContext
) {

    companion object {
        private val logger = LoggerFactory.getLogger(DockerHostClient::class.java)

        private const val TLINUX1_2_IMAGE = "/bkdevops/docker-builder1.2:v1"
        private const val TLINUX2_2_IMAGE = "/bkdevops/docker-builder2.2:v1"
    }

    @Value("\${dispatch.dockerBuildImagePrefix:#{null}}")
    val dockerBuildImagePrefix: String? = null

    fun startBuild(
        event: PipelineAgentStartupEvent,
        dockerIp: String,
        dockerHostPort: Int,
        poolNo: Int
    ) {
        val secretKey = ApiUtil.randomSecretKey()
        val id = pipelineDockerBuildDao.startBuild(
            dslContext = dslContext,
            projectId = event.projectId,
            pipelineId = event.pipelineId,
            buildId = event.buildId,
            vmSeqId = event.vmSeqId.toInt(),
            secretKey = secretKey,
            status = PipelineTaskStatus.RUNNING,
            zone = if (null == event.zone) {
                Zone.SHENZHEN.name
            } else {
                event.zone!!.name
            },
            dockerIp = dockerIp,
            poolNo = poolNo
        )
        val agentId = HashUtil.encodeLongId(id)
        redisUtils.setDockerBuild(
            id, secretKey,
            RedisBuild(
                vmName = agentId,
                projectId = event.projectId,
                pipelineId = event.pipelineId,
                buildId = event.buildId,
                vmSeqId = event.vmSeqId,
                channelCode = event.channelCode,
                zone = event.zone,
                atoms = event.atoms
            )
        )
        logger.info("secretKey: $secretKey")
        logger.info("agentId: $agentId")
        val dispatchType = event.dispatchType as DockerDispatchType
        logger.info("dockerHostBuild:(${event.userId},${event.projectId},${event.pipelineId},${event.buildId},${dispatchType.imageType?.name},${dispatchType.imageCode},${dispatchType.imageVersion},${dispatchType.credentialId},${dispatchType.credentialProject})")

        val dockerImage = if (dispatchType.imageType == ImageType.THIRD) {
            dispatchType.dockerBuildVersion
        } else {
            when (dispatchType.dockerBuildVersion) {
                DockerVersion.TLINUX1_2.value -> dockerBuildImagePrefix + TLINUX1_2_IMAGE
                DockerVersion.TLINUX2_2.value -> dockerBuildImagePrefix + TLINUX2_2_IMAGE
                else -> "$dockerBuildImagePrefix/${dispatchType.dockerBuildVersion}"
            }
        }
        logger.info("Docker images is: $dockerImage")
        var userName: String? = null
        var password: String? = null
        if (dispatchType.imageType == ImageType.THIRD) {
            if (!dispatchType.credentialId.isNullOrBlank()) {
                val projectId = if (dispatchType.credentialProject.isNullOrBlank()) {
                    logger.warn("dockerHostBuild:credentialProject=nullOrBlank,buildId=${event.buildId},credentialId=${dispatchType.credentialId}")
                    event.projectId
                } else {
                    dispatchType.credentialProject!!
                }
                val ticketsMap = CommonUtils.getCredential(
                    client = client,
                    projectId = projectId,
                    credentialId = dispatchType.credentialId!!,
                    type = CredentialType.USERNAME_PASSWORD
                )
                userName = ticketsMap["v1"] as String
                password = ticketsMap["v2"] as String
            }
        }

        val requestBody = DockerHostBuildInfo(
            projectId = event.projectId,
            agentId = agentId,
            pipelineId = event.pipelineId,
            buildId = event.buildId,
            vmSeqId = Integer.valueOf(event.vmSeqId),
            secretKey = secretKey,
            status = PipelineTaskStatus.RUNNING.status,
            imageName = dockerImage!!,
            containerId = "",
            wsInHost = true,
            poolNo = poolNo,
            registryUser = userName ?: "",
            registryPwd = password ?: "",
            imageType = dispatchType.imageType?.type,
            imagePublicFlag = false,
            imageRDType = null,
            containerHashId = ""
        )

        dockerBuildStart(dockerIp, dockerHostPort, requestBody, event)
    }

    fun endBuild(event: PipelineAgentShutdownEvent, dockerIp: String, containerId: String) {
        val requestBody = DockerHostBuildInfo(
            projectId = event.projectId,
            agentId = "",
            pipelineId = event.pipelineId,
            buildId = event.buildId,
            vmSeqId = event.vmSeqId?.toInt() ?: 0,
            secretKey = "",
            status = 0,
            imageName = "",
            containerId = containerId,
            wsInHost = true,
            poolNo = 0,
            registryUser = event.userId,
            registryPwd = "",
            imageType = "",
            imagePublicFlag = false,
            imageRDType = null,
            containerHashId = ""
        )

        val proxyUrl = dockerHostUtils.getIdc2DevnetProxyUrl("/api/docker/build/end", dockerIp)
        val request = Request.Builder().url(proxyUrl)
            .delete(
                RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    JsonUtil.toJson(requestBody)
                )
            )
            .addHeader("Accept", "application/json; charset=utf-8")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .build()

        OkhttpUtils.doHttp(request).use { resp ->
            val responseBody = resp.body()!!.string()
            logger.info("[${event.projectId}|${event.pipelineId}|${event.buildId}] End build Docker VM $dockerIp responseBody: $responseBody")
            val response: Map<String, Any> = jacksonObjectMapper().readValue(responseBody)
            if (response["status"] == 0) {
                response["data"] as Boolean
            } else {
                val msg = response["message"] as String
                logger.error("[${event.projectId}|${event.pipelineId}|${event.buildId}] End build Docker VM failed, msg: $msg")
                throw DockerServiceException("End build Docker VM failed, msg: $msg")
            }
        }
    }

    private fun dockerBuildStart(
        dockerIp: String,
        dockerHostPort: Int,
        requestBody: DockerHostBuildInfo,
        event: PipelineAgentStartupEvent,
        retryTime: Int = 0,
        unAvailableIpList: Set<String>? = null
    ) {
        val proxyUrl = dockerHostUtils.getIdc2DevnetProxyUrl("/api/docker/build/start", dockerIp, dockerHostPort)
        val request = Request.Builder().url(proxyUrl)
            .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), JsonUtil.toJson(requestBody)))
            .addHeader("Accept", "application/json; charset=utf-8")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .build()

        logger.info("[${event.projectId}|${event.pipelineId}|${event.buildId}|$retryTime] Start build Docker VM $dockerIp, url: $proxyUrl, requestBody: $requestBody")
        OkhttpUtils.doLongHttp(request).use { resp ->
            val responseBody = resp.body()!!.string()
            logger.info("[${event.projectId}|${event.pipelineId}|${event.buildId}|$retryTime] Start build Docker VM $dockerIp responseBody: $responseBody")
            val response: Map<String, Any> = jacksonObjectMapper().readValue(responseBody)
            when {
                response["status"] == 0 -> {
                    val containerId = response["data"] as String
                    logger.info("[${event.projectId}|${event.pipelineId}|${event.buildId}|$retryTime] update container: $containerId")
                    // 更新
                    pipelineDockerBuildDao.updateContainerId(
                        dslContext = dslContext,
                        buildId = event.buildId,
                        vmSeqId = Integer.valueOf(event.vmSeqId),
                        containerId = containerId
                    )
                }
                response["status"] == 1 -> {
                    // status== 1 重试三次
                    if (retryTime < 3) {
                        val unAvailableIpListLocal: Set<String> = unAvailableIpList?.plus(dockerIp) ?: setOf(dockerIp)
                        val retryTimeLocal = retryTime + 1
                        // 当前IP不可用，重新获取可用ip
                        val dockerIpLocalPair = dockerHostUtils.getAvailableDockerIp(event.projectId, event.pipelineId, event.vmSeqId, unAvailableIpListLocal)
                        dockerBuildStart(dockerIpLocalPair.first, dockerIpLocalPair.second, requestBody, event, retryTimeLocal, unAvailableIpListLocal)
                    } else {
                        logger.error("[${event.projectId}|${event.pipelineId}|${event.buildId}|$retryTime] Start build Docker VM failed, retry $retryTime times.")
                        throw DockerServiceException("Start build Docker VM failed, retry $retryTime times.")
                    }
                }
                else -> {
                    val msg = response["message"] as String
                    logger.error("[${event.projectId}|${event.pipelineId}|${event.buildId}|$retryTime] Start build Docker VM failed, msg: $msg")
                    throw DockerServiceException("Start build Docker VM failed, msg: $msg")
                }
            }
        }
    }
}