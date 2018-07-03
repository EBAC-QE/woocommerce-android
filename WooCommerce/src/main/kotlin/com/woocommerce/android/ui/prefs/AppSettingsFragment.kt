package com.woocommerce.android.ui.prefs

import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import kotlinx.android.synthetic.main.fragment_app_settings.*

class AppSettingsFragment : Fragment() {
    companion object {
        const val TAG = "app-settings"

        fun newInstance(): AppSettingsFragment {
            return AppSettingsFragment()
        }
    }

    interface AppSettingsListener {
        fun onRequestLogout()
    }

    private lateinit var listener: AppSettingsListener

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_app_settings, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (activity is AppSettingsListener) {
            listener = activity as AppSettingsListener
        } else {
            throw ClassCastException(context.toString() + " must implement AppSettingsListener")
        }

        textLogout.setOnClickListener {
            listener.onRequestLogout()
        }

        switchSendStats.isChecked = AnalyticsTracker.sendUsageStats
        switchSendStats.setOnClickListener {
            AnalyticsTracker.sendUsageStats = switchSendStats.isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.app_settings)
    }
}
