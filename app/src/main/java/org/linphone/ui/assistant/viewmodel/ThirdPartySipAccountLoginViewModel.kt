/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event

class ThirdPartySipAccountLoginViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Third Party SIP Account Login ViewModel]"
    }

    val username = MutableLiveData<String>()

    val authId = MutableLiveData<String>()

    val password = MutableLiveData<String>()

    val domain = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    val transport = MutableLiveData<String>()

    val internationalPrefix = MutableLiveData<String>()

    val internationalPrefixIsoCountryCode = MutableLiveData<String>()

    val showPassword = MutableLiveData<Boolean>()

    val expandAdvancedSettings = MutableLiveData<Boolean>()

    val proxy = MutableLiveData<String>()

    val outboundProxy = MutableLiveData<String>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val registrationInProgress = MutableLiveData<Boolean>()

    val accountLoggedInEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val accountLoginErrorEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val defaultTransportIndexEvent: MutableLiveData<Event<Int>> by lazy {
        MutableLiveData()
    }

    val availableTransports = arrayListOf<String>()

    private lateinit var newlyCreatedAuthInfo: AuthInfo
    private lateinit var newlyCreatedAccount: Account

    // BizVoIP: an attempt still waiting for its registration, and which attempt it is, so that a timeout or
    // leaving the form ends it (core thread only)
    private var awaitingRegistration = false
    private var loginAttempt = 0

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (account == newlyCreatedAccount) {
                Log.i("$TAG Newly created account registration state is [$state] ($message)")

                if (state == RegistrationState.Ok) {
                    awaitingRegistration = false
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    // Set new account as default
                    core.defaultAccount = newlyCreatedAccount
                    accountLoggedInEvent.postValue(Event(true))
                } else if (state == RegistrationState.Failed) {
                    awaitingRegistration = false
                    registrationInProgress.postValue(false)
                    core.removeListener(this)

                    // BizVoIP: say what to check, as on iOS, rather than the SDK's reason code. The details
                    // are wrong when the server refuses them (403, 401) or doesn't know the account (404);
                    // anything else is the server out of reach
                    val wrongDetails = when (account.error) {
                        Reason.Forbidden, Reason.Unauthorized, Reason.NotFound -> true
                        else -> false
                    }
                    val error = AppUtils.getString(
                        if (wrongDetails) {
                            R.string.assistant_account_login_credentials_error
                        } else {
                            R.string.assistant_account_login_unreachable_error
                        }
                    )
                    accountLoginErrorEvent.postValue(Event(error))

                    Log.e("$TAG Account failed to REGISTER [$message] (${account.error}), removing it")
                    removeNewlyCreatedAccount(core)
                }
            }
        }
    }

    init {
        showPassword.value = false
        expandAdvancedSettings.value = false
        registrationInProgress.value = false

        loginEnabled.addSource(username) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(password) {
            loginEnabled.value = isLoginButtonEnabled()
        }
        loginEnabled.addSource(domain) {
            loginEnabled.value = isLoginButtonEnabled()
        }

        // TODO: handle formatting errors ?

        availableTransports.add(TransportType.Udp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tcp.name.uppercase(Locale.getDefault()))
        availableTransports.add(TransportType.Tls.name.uppercase(Locale.getDefault()))

        coreContext.postOnCoreThread {
            domain.postValue(corePreferences.thirdPartySipAccountDefaultDomain)
            // BizVoIP: through our Flexisip gateway, which wakes the app with a push for incoming calls
            val defaultProxy = corePreferences.thirdPartySipAccountDefaultProxy
            proxy.postValue(defaultProxy)
            outboundProxy.postValue(defaultProxy)

            val defaultTransport = corePreferences.thirdPartySipAccountDefaultTransport.uppercase(
                Locale.getDefault()
            )
            val index = if (defaultTransport.isNotEmpty()) {
                availableTransports.indexOf(defaultTransport)
            } else {
                availableTransports.size - 1
            }
            defaultTransportIndexEvent.postValue(Event(index))
        }
    }

    @UiThread
    override fun onCleared() {
        // BizVoIP: leaving the form while an attempt still waits for its registration ends the attempt, so a
        // later failure can't delete an account behind the user's back
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
            if (awaitingRegistration) {
                Log.w("$TAG Leaving the form while the new account is still registering, removing it")
                awaitingRegistration = false
                removeNewlyCreatedAccount(core)
            }
        }
        super.onCleared()
    }

    @UiThread
    fun login() {
        // BizVoIP: a username written as 201@azienda.voip.biztems.it (with or without sip:) brings its own
        // domain, as on iOS
        var typedUser = username.value.orEmpty().trim().removePrefix("sips:").removePrefix("sip:")
        if (typedUser.contains("@")) {
            domain.value = typedUser.substringAfter("@")
            typedUser = typedUser.substringBefore("@")
        }
        username.value = typedUser

        coreContext.postOnCoreThread { core ->
            core.loadConfigFromXml(corePreferences.thirdPartyDefaultValuesPath)

            // Remove sip: in front of domain, just in case...
            val domainValue = domain.value.orEmpty().trim()
            val domainWithoutSip = if (domainValue.startsWith("sip:")) {
                domainValue.substring("sip:".length)
            } else {
                domainValue
            }
            val domainAddress = Factory.instance().createAddress("sip:$domainWithoutSip")
            val port = domainAddress?.port ?: -1
            if (port != -1) {
                Log.w("$TAG It seems a port [$port] was set in the domain [$domainValue], removing it from SIP identity but setting it to proxy server URI")
            }
            val domain = domainAddress?.domain ?: domainWithoutSip

            // Allow to enter SIP identity instead of simply username
            // in case identity domain doesn't match proxy domain
            var user = username.value.orEmpty().trim()
            if (user.startsWith("sip:")) {
                user = user.substring("sip:".length)
            } else if (user.startsWith("sips:")) {
                user = user.substring("sips:".length)
            }
            if (user.contains("@")) {
                user = user.split("@")[0]
            }

            val userId = authId.value.orEmpty().trim()

            Log.i("$TAG Parsed username is [$user], user ID [$userId] and domain [$domain]")
            val identity = "sip:$user@$domain"
            val identityAddress = Factory.instance().createAddress(identity)
            if (identityAddress == null) {
                Log.e("$TAG Can't parse [$identity] as Address!")
                showRedToast(R.string.assistant_login_cant_parse_address_toast, R.drawable.warning_circle)
                return@postOnCoreThread
            }
            Log.i("$TAG Computed SIP identity is [${identityAddress.asStringUriOnly()}]")

            val accounts = core.accountList
            val found = accounts.find {
                it.params.identityAddress?.weakEqual(identityAddress) == true
            }
            if (found != null) {
                Log.w("$TAG An account with the same identity address [${found.params.identityAddress?.asStringUriOnly()}] already exists, do not add it again!")
                showRedToast(R.string.assistant_account_login_already_connected_error, R.drawable.warning_circle)
                return@postOnCoreThread
            }

            newlyCreatedAuthInfo = Factory.instance().createAuthInfo(
                user,
                userId,
                password.value.orEmpty().trim(),
                null,
                null,
                domainAddress?.domain ?: domainValue
            )
            core.addAuthInfo(newlyCreatedAuthInfo)

            val accountParams = core.createAccountParams()

            if (displayName.value.orEmpty().isNotEmpty()) {
                identityAddress.displayName = displayName.value.orEmpty().trim()
            }
            accountParams.identityAddress = identityAddress

            val outboundProxyValue = outboundProxy.value.orEmpty().trim()
            val outboundProxyAddress = if (outboundProxyValue.isNotEmpty()) {
                val server = if (outboundProxyValue.startsWith("sip:")) {
                    outboundProxyValue
                } else {
                    "sip:$outboundProxyValue"
                }
                Factory.instance().createAddress(server)
            } else {
                null
            }
            if (outboundProxyAddress != null) {
                outboundProxyAddress.transport = when (transport.value.orEmpty().trim()) {
                    TransportType.Tcp.name.uppercase(Locale.getDefault()) -> TransportType.Tcp
                    TransportType.Tls.name.uppercase(Locale.getDefault()) -> TransportType.Tls
                    else -> TransportType.Udp
                }
                Log.i("$TAG Created outbound proxy server SIP address [${outboundProxyAddress.asStringUriOnly()}]")
                accountParams.setRoutesAddresses(arrayOf(outboundProxyAddress))
            }

            val proxyServerValue = proxy.value.orEmpty().trim()
            val proxyServerAddress = if (proxyServerValue.isNotEmpty()) {
                val server = if (proxyServerValue.startsWith("sip:")) {
                    proxyServerValue
                } else {
                    "sip:$proxyServerValue"
                }
                Factory.instance().createAddress(server)
            } else {
                outboundProxyAddress ?: domainAddress ?: Factory.instance().createAddress("sip:$domainWithoutSip")
            }
            proxyServerAddress?.transport = when (transport.value.orEmpty().trim()) {
                TransportType.Tcp.name.uppercase(Locale.getDefault()) -> TransportType.Tcp
                TransportType.Tls.name.uppercase(Locale.getDefault()) -> TransportType.Tls
                else -> TransportType.Udp
            }
            Log.i("$TAG Created proxy server SIP address [${proxyServerAddress?.asStringUriOnly()}]")
            accountParams.serverAddress = proxyServerAddress
            Log.i("$TAG Is outbound proxy enabled ? [${accountParams.isOutboundProxyEnabled}]")

            val prefix = internationalPrefix.value.orEmpty().trim()
            val isoCountryCode = internationalPrefixIsoCountryCode.value.orEmpty()
            if (prefix.isNotEmpty()) {
                val prefixDigits = if (prefix.startsWith("+")) {
                    prefix.substring(1)
                } else {
                    prefix
                }
                if (prefixDigits.isNotEmpty()) {
                    Log.i(
                        "$TAG Setting international prefix [$prefixDigits]($isoCountryCode) in account params"
                    )
                    accountParams.internationalPrefix = prefixDigits
                    accountParams.internationalPrefixIsoCountryCode = isoCountryCode
                }
            }

            newlyCreatedAccount = core.createAccount(accountParams)

            registrationInProgress.postValue(true)
            core.addListener(coreListener)
            core.addAccount(newlyCreatedAccount)

            // BizVoIP: a registration that gets no answer at all still gives the form back
            awaitingRegistration = true
            val attempt = ++loginAttempt
            coreContext.postOnCoreThreadDelayed({ c ->
                if (attempt == loginAttempt && awaitingRegistration) {
                    Log.e("$TAG No answer to the new account's REGISTER after 40 s, removing it")
                    awaitingRegistration = false
                    registrationInProgress.postValue(false)
                    c.removeListener(coreListener)
                    removeNewlyCreatedAccount(c)
                    accountLoginErrorEvent.postValue(
                        Event(AppUtils.getString(R.string.assistant_account_login_unreachable_error))
                    )
                }
            }, 40000)
        }
    }

    @WorkerThread
    private fun removeNewlyCreatedAccount(core: Core) {
        // biztems: the core keeps a copy of newlyCreatedAuthInfo, so removing our own
        // object failed and the rejected password was reused on every retry
        newlyCreatedAccount.findAuthInfo()?.let { core.removeAuthInfo(it) }
        core.removeAccount(newlyCreatedAccount)
    }

    @UiThread
    fun toggleShowPassword() {
        showPassword.value = showPassword.value == false
    }

    @UiThread
    private fun isLoginButtonEnabled(): Boolean {
        // BizVoIP: every account has a password; the domain can come with the username (201@azienda...)
        val user = username.value.orEmpty().trim()
        return user.isNotEmpty() && password.value.orEmpty().trim().isNotEmpty() &&
            (domain.value.orEmpty().trim().isNotEmpty() || user.contains("@"))
    }

    @UiThread
    fun toggleAdvancedSettingsExpand() {
        expandAdvancedSettings.value = expandAdvancedSettings.value == false
    }
}
