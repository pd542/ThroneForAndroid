package io.nekohasekai.sagernet.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SimFrontProxyEntity
import io.nekohasekai.sagernet.database.SimFrontProxyRepo
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.utils.SimStateAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › SIM front proxy: one "this SIM uses that front proxy" binding per row.
 *
 * The rows are global (see [SimFrontProxyEntity]) and take the place of the group's front proxy while
 * the bound SIM carries mobile data, so starting the proxy on cellular switches the front proxy by
 * itself. The screen also reports which SIM is active now, because a binding that never matches is
 * indistinguishable from one that has no effect.
 */
class SimFrontProxySettingsFragment : SettingsScreenFragment(R.xml.settings_sim_front_proxy) {

    private val selectProfile: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onProfilePicked(result)
        }

    /** The row whose front proxy is being edited, and the slot/carrier it should be saved with. */
    private var editing: SimFrontProxyEntity? = null

    /** The draft of the add dialog; the profile is picked after the slot and carrier are confirmed. */
    private var pendingSlot = 1
    private var pendingCarrier = ""

    override fun bind() {
        pref<Preference>(KEY_CURRENT).setOnPreferenceClickListener {
            requestPhoneState()
            true
        }
        pref<Preference>(KEY_ADD).setOnPreferenceClickListener {
            showAddDialog()
            true
        }
        pref<Preference>(KEY_CLEAR).setOnPreferenceClickListener {
            confirmClear()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ------------------------------------------------------------------------------------------------ state

    private fun reload() = lifecycleScope.launch {
        val (state, bindings) = withContext(Dispatchers.IO) {
            SimStateAccess.read(requireContext()) to SimFrontProxyRepo.all()
        }
        showCurrentSim(state)
        showBindings(bindings)
    }

    private fun showCurrentSim(state: SimStateAccess.State) {
        val status = SimStateAccess.status(requireContext())
        pref<Preference>(KEY_CURRENT).summary = when {
            status == SimStateAccess.Status.NEED_PHONE_STATE -> getString(R.string.sim_front_proxy_need_permission)
            !state.available -> getString(R.string.sim_front_proxy_no_sim)
            else -> getString(
                R.string.sim_front_proxy_current_sum,
                state.slot,
                state.carrierName.ifEmpty { state.carrier.ifEmpty { SimFrontProxyEntity.ANY_CARRIER } },
            )
        }
    }

    /** The rows, each showing the slot, the carrier and the front proxy it binds. */
    private suspend fun showBindings(bindings: List<SimFrontProxyEntity>) {
        val labels = withContext(Dispatchers.IO) {
            bindings.associate { it.id to profileLabel(it.profileId) }
        }
        val category = pref<PreferenceCategory>(KEY_LIST)
        category.removeAll()
        category.isVisible = bindings.isNotEmpty()
        for (binding in bindings) {
            val preference = Preference(requireContext()).apply {
                key = KEY_ROW_PREFIX + binding.id
                isPersistent = false
                title = getString(R.string.sim_front_proxy_row, binding.slot, binding.carrierLabel())
                summary = labels[binding.id]
                    ?: getString(R.string.sim_front_proxy_missing_profile, binding.profileId)
                setOnPreferenceClickListener {
                    pickProfile(binding)
                    true
                }
            }
            category.addPreference(preference)
        }
    }

    /** get_proxy_name: "[group] name"; null when the profile or its group is gone. */
    private suspend fun profileLabel(id: Long): String? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        val profile = ProfileManager.getProfile(id) ?: return@withContext null
        val group = io.nekohasekai.sagernet.database.GroupRepo.get(profile.groupId)
            ?: return@withContext profile.displayName()
        "[" + group.displayName() + "] " + profile.displayName()
    }

    // ------------------------------------------------------------------------------------------------ add

    private fun showAddDialog() {
        pendingSlot = 1
        pendingCarrier = ""
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.sim_front_proxy_add)
        val view = layoutInflater.inflate(R.layout.dialog_sim_front_proxy, null)
        val slotGroup = view.findViewById<android.widget.RadioGroup>(R.id.simSlotGroup)
        val carrierInput = view.findViewById<android.widget.EditText>(R.id.simCarrier)
        builder.setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingSlot = when (slotGroup.checkedRadioButtonId) {
                    R.id.simSlot1 -> 1
                    R.id.simSlot2 -> 2
                    else -> 3 // "other": the third button carries every slot beyond the two common ones.
                }
                pendingCarrier = carrierInput.text?.toString()?.trim().orEmpty()
                pickProfile(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sim_front_proxy_clear)
            .setMessage(R.string.sim_front_proxy_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SimFrontProxyRepo.reset() }
                    needReload()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------------------------------------ profile picking

    private fun pickProfile(binding: SimFrontProxyEntity?) {
        editing = binding
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) {
                binding?.let { ProfileManager.getProfile(it.profileId) }
            }
            selectProfile.launch(Intent(requireContext(), ProfileSelectActivity::class.java).apply {
                selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
            })
        }
    }

    /** Auto selectors are refused, as in the group's front / landing pickers: they move server on their own. */
    private fun onProfilePicked(result: ActivityResult) {
        // A pending add has no row yet, so editing stays null; the draft in pendingSlot / pendingCarrier is enough.
        val binding = editing
        if (result.resultCode != android.app.Activity.RESULT_OK) return
        val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: return
        if (id <= 0) return
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { ProfileManager.getProfile(id) } ?: return@launch
            if (profile.type == AUTO_SELECTOR) {
                toast(R.string.grp_no_auto_selector)
                return@launch
            }
            withContext(Dispatchers.IO) {
                SimFrontProxyRepo.put(
                    SimFrontProxyEntity(
                        id = binding?.id ?: 0L,
                        slot = binding?.slot ?: pendingSlot,
                        carrier = binding?.carrier ?: pendingCarrier,
                        profileId = id,
                    )
                )
            }
            needReload()
            reload()
        }.invokeOnCompletion {
            // Drop the row so the next add starts from the draft again, not from the binding just saved.
            editing = null
        }
    }

    // ------------------------------------------------------------------------------------------------ permission

    /** Reading the active SIM needs READ_PHONE_STATE; the system dialog is the only way to grant it. */
    private val requestPhoneState: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private fun requestPhoneState() {
        if (SimStateAccess.status(requireContext()) == SimStateAccess.Status.NEED_PHONE_STATE) {
            requestPhoneState.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            return
        }
        reload()
    }

    private companion object {
        const val KEY_CURRENT = "simFrontProxyCurrent"
        const val KEY_ADD = "simFrontProxyAdd"
        const val KEY_LIST = "simFrontProxyList"
        const val KEY_CLEAR = "simFrontProxyClear"
        const val KEY_ROW_PREFIX = "simFrontProxyRow"

        /** AutoSelectorProfiles.TYPE; kept as a literal to avoid the background package from the UI. */
        const val AUTO_SELECTOR = "auto-selector"
    }
}
