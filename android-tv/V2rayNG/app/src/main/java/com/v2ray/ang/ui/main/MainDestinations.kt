package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Разделы, в которые уводит вкладка настроек.
 *
 * Раньше это был список бокового меню; меню убрано вместе с переходом на
 * нижние вкладки, но сам перечень разделов остался прежним.
 */
enum class MainDestination(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Renew(R.drawable.ic_renew_24dp, R.string.title_renew),
    Subscriptions(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting),
    PerAppProxy(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings),
    Routing(R.drawable.ic_routing_24dp, R.string.routing_settings_title),
    UserAssets(R.drawable.ic_file_24dp, R.string.title_user_asset_setting),
    Settings(R.drawable.ic_settings_24dp, R.string.title_settings),
    Support(R.drawable.ic_telegram_24dp, R.string.title_support_chat),
    LinkDevice(R.drawable.ic_qu_scan_24dp, R.string.title_link_device),
    Promotion(R.drawable.ic_promotion_24dp, R.string.title_pref_promotion),
    Logcat(R.drawable.ic_logcat_24dp, R.string.title_logcat),
    CheckUpdate(R.drawable.ic_check_update_24dp, R.string.update_check_for_update),
    BackupRestore(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore),
    About(R.drawable.ic_about_24dp, R.string.title_about)
}
