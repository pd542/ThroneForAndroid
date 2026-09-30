package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SimFrontProxyRepo
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.outbound.config.TestCandidate
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.SimStateAccess

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the current route profile and the rule-set list from
 * RouteManager (main configs only; test configs never read them), the landing / front proxy from the profile's group.
 *
 * The front proxy is the group's slot unless a SIM binding changes it: see [resolveFront].
 */
object CoreConfigs {

    /** "No front proxy": the generator treats any id <= 0 as unset. */
    private const val NONE = -1L

    /** Stored profiles by id, each row parsed once per generation. */
    private class DatabaseProfiles : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) { SagerDatabase.proxyDao.getById(id)?.outbound }
    }

    fun generator(routing: RoutingInput = RoutingInput.DEFAULT): ConfigGenerator =
        ConfigGenerator(DatabaseProfiles(), SettingsMapper.generatorSettings(), SettingsMapper.buildContext(), routing)

    /** The main config of [profile]; throws with the generator's message when it cannot be built. */
    fun buildMain(profile: ProxyEntity): GeneratedConfig {
        val (landing, front) = groupProxies(profile.groupId, profile.id)
        val generated = generator(SettingsMapper.routingInput()).build(profile.id, landing, front)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /** One test config for every candidate at once, each under its own group's landing / front proxy. */
    fun buildTest(profileIds: List<Long>): GeneratedConfig {
        val groups = HashMap<Long, Pair<Long, Long>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front) = groups.getOrPut(groupId) { groupProxies(groupId, id) }
            TestCandidate(id, landing, front)
        }
        return generator().buildTest(candidates)
    }

    /** (landing_proxy_id, front_proxy_id) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long, profileId: Long): Pair<Long, Long> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return -1L to -1L
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = resolveFront(group.frontProxyId.takeIf { it > 0 } ?: -1L, profileId)
        return landing to front
    }

    /**
     * The front proxy to build [profileId] with: the SIM binding of the active data SIM when there is
     * one, else the group's own [groupFront].
     *
     * A binding that names [profileId] itself resolves to "none": the started profile must not be
     * chained in front of itself, which the generator would happily expand into a self-referencing
     * hop. That is also what makes "the front proxy is the config I am already running" a no-op
     * instead of a duplicated hop.
     *
     * An unbound SIM normally keeps the group's front proxy, but a SIM the user moved to from a bound
     * one cancels it instead: the front proxy is in effect only while the bound SIM is in use.
     */
    private fun resolveFront(groupFront: Long, profileId: Long): Long {
        val bound = try {
            val state = SimStateAccess.read(SagerNet.application)
            if (!state.available) {
                // No readable SIM (no permission, no subscription): keep the group's front proxy and
                // forget the last binding, so a SIM that appears later starts from a clean slate.
                SimFrontProxyRepo.forgetApplied()
                NONE
            } else {
                SimFrontProxyRepo.resolve(state.slot, state.carrier)
            }
        } catch (e: Throwable) {
            // A binding must never keep the proxy from starting: fall back to the group's slot.
            Logs.w(e)
            SimFrontProxyRepo.forgetApplied()
            NONE
        }
        if (bound <= 0L) {
            // Left a bound SIM for a SIM with no binding: the front proxy was only meant for the
            // bound one, so drop it rather than fall back to the group's.
            return if (SimFrontProxyRepo.shouldClearForUnbound()) NONE else groupFront
        }
        // The bound profile has to exist, or the generator fails with "missing profile" and the app cannot connect.
        if (ProfileManager.getProfile(bound) == null) {
            Logs.w("SIM front proxy $bound is gone, falling back to the group's front proxy")
            return groupFront
        }
        if (bound == profileId) return NONE
        return bound
    }
}
