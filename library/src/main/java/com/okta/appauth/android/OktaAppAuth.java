/*
 * Copyright (c) 2017, Okta, Inc. and/or its affiliates. All rights reserved.
 * The Okta software accompanied by this notice is provided pursuant to the Apache License,
 * Version 2.0 (the "License.")
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and limitations under the
 * License.
 */

package com.okta.appauth.android;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.AnyThread;
import android.support.annotation.ColorInt;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.support.customtabs.CustomTabsIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthState.AuthStateAction;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.AuthorizationServiceDiscovery;
import net.openid.appauth.ClientAuthentication;
import net.openid.appauth.ClientAuthentication.UnsupportedAuthenticationMethod;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenResponse;
import net.openid.appauth.connectivity.DefaultConnectionBuilder;
import okio.Okio;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SDK's main interaction point with an application. Other operations will stem from this
 * class in order to provide a single interface for callers.
 */
@SuppressWarnings("WeakerAccess") // This class could be extended by callers
public class OktaAppAuth {

    private static final String TAG = "OktaAppAuth";

    private static final AtomicReference<WeakReference<OktaAppAuth>> INSTANCE_REF =
            new AtomicReference<>(new WeakReference<OktaAppAuth>(null));

    protected AuthorizationService mAuthService;
    protected AuthStateManager mAuthStateManager;
    protected OAuthClientConfiguration mConfiguration;

    protected final AtomicReference<OktaAuthListener> mInitializationListener =
            new AtomicReference<>();
    protected final AtomicReference<String> mClientId = new AtomicReference<>();
    protected final AtomicReference<AuthorizationRequest> mAuthRequest = new AtomicReference<>();
    protected final AtomicReference<CustomTabsIntent> mAuthIntent = new AtomicReference<>();
    protected CountDownLatch mAuthIntentLatch = new CountDownLatch(1);

    protected ExecutorService mExecutor;

    @ColorInt
    protected int mCustomTabColor;

    /**
     * Retrieve the manager object via the static {@link WeakReference} or construct a new instance.
     *
     * @param context The Context from which to get the application's environment
     * @return am OktaAppAuth object
     */
    @AnyThread
    public static OktaAppAuth getInstance(@NonNull Context context) {
        OktaAppAuth oktaAppAuth = INSTANCE_REF.get().get();
        if (oktaAppAuth == null) {
            oktaAppAuth = new OktaAppAuth(context.getApplicationContext());
            INSTANCE_REF.set(new WeakReference<>(oktaAppAuth));
        }

        return oktaAppAuth;
    }

    /**
     * Constructs an OktaAppAuth object. Provided the Context to initialize any other components.
     *
     * @param context The application Context
     */
    @AnyThread
    protected OktaAppAuth(Context context) {
        mExecutor = Executors.newSingleThreadExecutor();
        mAuthStateManager = AuthStateManager.getInstance(context);
        mConfiguration = OAuthClientConfiguration.getInstance(context);
    }

    /**
     * Initializes the OktaAppAuth object. This will fetch an OpenID Connect discovery document
     * from the issuer in the configuration to configure this instance for use. This method
     * will not customize the CustomTabs session. If you would like to customize the
     * CustomTabs session, use {@link #init(Context, OktaAuthListener, int)}
     *
     * @param context The application context
     * @param listener An OktaAuthSuccessListener that will be called once the initialization is
     *     complete
     */
    @AnyThread
    public void init(
            final Context context,
            final OktaAuthListener listener) {
        init(context, listener, 0);
    }

    /**
     * Initializes the OktaAppAuth object. This will fetch an OpenID Connect discovery document
     * from the issuer in the configuration to configure this instance for use.
     *
     * @param context The application context
     * @param listener An OktaAuthListener that will be called once the initialization is
     *     complete
     * @param customTabColor The color that will be passed to
     *     {@link CustomTabsIntent.Builder#setToolbarColor(int)}
     */
    @AnyThread
    public void init(
            final Context context,
            final OktaAuthListener listener,
            @ColorInt int customTabColor) {
        mCustomTabColor = customTabColor;
        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                doInit(context, listener);
            }
        });
    }

    /**
     * Logs in a user and acquires authorization tokens for that user. Uses a login hint provided
     * by a {@link LoginHintChangeHandler} if available.
     *
     * @param context The application context
     * @param completionIntent The PendingIntent to direct the flow upon successful completion
     * @param cancelIntent The PendingIntent to direct the flow upon cancellation or failure
     */
    public void login(
            final Context context,
            final PendingIntent completionIntent,
            final PendingIntent cancelIntent) {
        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                doAuth(
                        TokenExchangeActivity.createStartIntent(
                                context.getApplicationContext(),
                                completionIntent,
                                cancelIntent),
                        cancelIntent);
            }
        });
    }

    /**
     * Logs a user out. This method will discard a user's tokens and remove them from the
     * device's storage.
     */
    public void logout() {
        // discard the authorization and token state, but retain the configuration and
        // dynamic client registration (if applicable), to save from retrieving them again.
        AuthState currentState = mAuthStateManager.getCurrent();
        AuthState clearedState =
                new AuthState(currentState.getAuthorizationServiceConfiguration());
        if (currentState.getLastRegistrationResponse() != null) {
            clearedState.update(currentState.getLastRegistrationResponse());
        }
        mAuthStateManager.replace(clearedState);
    }

    /**
     * Disposes state that will not normally be handled by garbage collection. This should be
     * called when this service is no longer required, including when any owning activity is
     * paused or destroyed (i.e. in {@link android.app.Activity#onDestroy()}).
     */
    public void dispose() {
        if (mAuthService != null) {
            mAuthService.dispose();
        }
    }

    /**
     * Determines whether a user is currently authorized given the current (or updated)
     * configuration.
     *
     * @return {@code true} if a user is logged in and the configuration hasn't changed;
     *     {@code false} otherwise
     */
    @AnyThread
    public boolean isUserLoggedIn() {
        return mAuthStateManager.getCurrent().isAuthorized() &&
                !mConfiguration.hasConfigurationChanged();
    }

    /**
     * Determines whether there is a refresh token in the application's storage.
     *
     * @return {@code true} if a refresh token is present; {@code false} otherwise
     */
    public boolean hasRefreshToken() {
        return mAuthStateManager.getCurrent().getRefreshToken() != null;
    }

    /**
     * Determines whether there is an access token in the application's storage.
     *
     * @return {@code true} if an access token is present; {@code false} otherwise
     */
    public boolean hasAccessToken() {
        return mAuthStateManager.getCurrent().getAccessToken() != null;
    }

    /**
     * The expiration time of the current access token (if available), as milliseconds from the
     * UNIX epoch (consistent with {@link System#currentTimeMillis()}).
     *
     * @return Milliseconds from the UNIX epoch at which point the access token will expire
     */
    public Long getAccessTokenExpirationTime() {
        return mAuthStateManager.getCurrent().getAccessTokenExpirationTime();
    }

    /**
     * Refreshes the access token if a refresh token is available to do so. This method will
     * do nothing if there is no refresh token.
     *
     * @param listener An OktaAuthSuccessListener that will be called once the refresh is complete
     */
    public void refreshAccessToken(final OktaAuthListener listener) {
        if (!hasRefreshToken()) {
            Log.d(TAG, "Calling refreshAccessToken without a refresh token");
            return;
        }

        ClientAuthentication clientAuthentication;
        try {
            clientAuthentication = mAuthStateManager.getCurrent().getClientAuthentication();
        } catch (UnsupportedAuthenticationMethod ex) {
            Log.e(TAG, "Token request cannot be made; client authentication for the token "
                    + "endpoint could not be constructed (%s)", ex);
            return;
        }

        mAuthService.performTokenRequest(
                mAuthStateManager.getCurrent().createTokenRefreshRequest(),
                clientAuthentication,
                new AuthorizationService.TokenResponseCallback() {
                    @Override
                    public void onTokenRequestCompleted(@Nullable TokenResponse tokenResponse,
                            @Nullable AuthorizationException authException) {
                        handleAccessTokenResponse(
                                tokenResponse,
                                authException,
                                listener);
                    }
                });
    }

    /**
     * Determines whether there is an ID token in the application's storage.
     *
     * @return {@code true} if an ID token is present; {@code false} otherwise
     */
    public boolean hasIdToken() {
        return mAuthStateManager.getCurrent().getIdToken() != null;
    }

    /**
     * Fetches the user's information from the userinfo OpenID Connect endpoint. Provides the
     * user info as a JSONObject through a callback interface if successful, and calls a failure
     * method on the callback in case of failure.
     *
     * @param callback An OktaAuthActionCallback providing the user info as a JSONObject on success
     *     while calling one of the failure methods in case of a failure
     */
    public void getUserInfo(final OktaAuthActionCallback<JSONObject> callback) {
        performAuthorizedRequest(new BearerAuthRequest() {
            @NonNull
            @Override
            public HttpURLConnection createRequest() throws Exception {
                AuthorizationServiceDiscovery discovery =
                        mAuthStateManager.getCurrent()
                                .getAuthorizationServiceConfiguration()
                                .discoveryDoc;

                URL userInfoEndpoint = new URL(discovery.getUserinfoEndpoint().toString());

                HttpURLConnection conn = (HttpURLConnection) userInfoEndpoint.openConnection();
                conn.setInstanceFollowRedirects(false);
                return conn;
            }

            @Override
            public void onSuccess(@NonNull InputStream response) {
                String jsonString;
                try {
                    jsonString = Okio.buffer(Okio.source(response))
                            .readString(Charset.forName("UTF-8"));
                } catch (IOException e) {
                    onFailure(-1, e);
                    return;
                }
                JSONObject jsonObject;
                try {
                    jsonObject = new JSONObject(jsonString);
                } catch (JSONException e) {
                    onFailure(-1, e);
                    return;
                }

                callback.onSuccess(jsonObject);
            }

            @Override
            public void onTokenFailure(@NonNull AuthorizationException ex) {
                Log.e(TAG, "Authorization error when fetching user info");
                callback.onTokenFailure(ex);
            }

            @Override
            public void onFailure(int httpResponseCode, Exception ex) {
                if (ex != null) {
                    Log.e(TAG, "Error when querying userinfo endpoint", ex);
                } else {
                    Log.e(TAG, "Bad status code when querying userinfo endpoint: " +
                            httpResponseCode);
                }
                callback.onFailure(httpResponseCode, ex);
            }
        });
    }

    /**
     * <p>
     * Performs an authorized action with a fresh Okta access token. With the BearerAuthRequest
     * interface, you provide the {@link HttpURLConnection} object and the access token will
     * automatically be added to the "Authorization" header with the standard OAuth 2.0 prefix of
     * "Bearer ". Tokens will be automatically refreshed if needed automatically.
     *</p>
     *
     * <p>
     * The following code is provided as an example for how you can leverage this method with
     * the BearerAuthRequest interface.
     * </p>
     * <pre>
     *     final URL myUrl; // some protected URL
     *
     *     performAuthorizedRequest(new BearerAuthRequest() {
     *         &#64;NonNull
     *         &#64;Override
     *         public HttpURLConnection createRequest() throws Exception {
     *             HttpURLConnection conn = (HttpURLConnection) myUrl.openConnection();
     *             conn.setInstanceFollowRedirects(false); // recommended during authorized calls
     *             return conn;
     *         }
     *
     *         &#64;Override
     *         public void onSuccess(@NonNull InputStream response) {
     *             // Handle successful response in the input stream
     *         }
     *
     *         &#64;Override
     *         public void onTokenFailure(@NonNull AuthorizationException ex) {
     *             // Handle failure to acquire new tokens from Okta
     *         }
     *
     *         &#64;Override
     *         public void onFailure(int httpResponseCode, Exception ex) {
     *             // Handle failure to make your authorized request or a response with a 4xx or
     *             // 5xx HTTP status response code
     *         }
     *     );
     * </pre>
     *
     * @param action An BearerAuthRequest detailing the action to take with success and failure
     *     handlers
     */
    public void performAuthorizedRequest(final BearerAuthRequest action) {
        if (mAuthStateManager.getCurrent().getNeedsTokenRefresh() && !hasRefreshToken()) {
            Log.i(TAG, "Attempted to take an authorized action, but don't have a refresh token");
            throw new IllegalStateException("No refresh token to get new authorization");
        }

        mAuthStateManager.getCurrent().performActionWithFreshTokens(
                mAuthService,
                new AuthStateAction() {
                    @Override
                    public void execute(@Nullable String accessToken, @Nullable String idToken,
                            @Nullable AuthorizationException ex) {
                        doAuthorizedAction(accessToken, idToken, ex, action);
                    }
                });
    }

    @WorkerThread
    private void doInit(final Context context, final OktaAuthListener listener) {
        mInitializationListener.set(listener);
        recreateAuthorizationService(context);

        if (mConfiguration.hasConfigurationChanged()) {
            // discard any existing authorization state due to the change of configuration
            Log.i(TAG, "Configuration change detected, discarding old state");
            mAuthStateManager.replace(new AuthState());
            if (!mConfiguration.isValid()) {
                Log.e(TAG, "Configuration was invalid: " + mConfiguration.getConfigurationError());
                listener.onTokenFailure(
                        AuthorizationException.GeneralErrors.INVALID_DISCOVERY_DOCUMENT);
            }
            mConfiguration.acceptConfiguration();
        }

        if (mAuthStateManager.getCurrent().getAuthorizationServiceConfiguration() != null) {
            // configuration is already created, skip to client initialization
            Log.i(TAG, "auth config already established");
            initializeClient();
            return;
        }

        Log.i(TAG, "Retrieving OpenID discovery doc");
        AuthorizationServiceConfiguration.fetchFromUrl(
                mConfiguration.getDiscoveryUri(),
                new AuthorizationServiceConfiguration.RetrieveConfigurationCallback() {
                    @Override
                    public void onFetchConfigurationCompleted(
                            @Nullable AuthorizationServiceConfiguration serviceConfiguration,
                            @Nullable AuthorizationException ex) {
                        handleConfigurationRetrievalResult(serviceConfiguration, ex);
                    }
                },
                DefaultConnectionBuilder.INSTANCE);
    }

    /*
     * Initiates the client ID from the configuration.
     */
    @WorkerThread
    private void initializeClient() {
        Log.i(TAG, "Using static client ID: " + mConfiguration.getClientId());
        // use a statically configured client ID
        mClientId.set(mConfiguration.getClientId());
        initializeAuthRequest();
    }


    @WorkerThread
    private void initializeAuthRequest() {
        createAuthRequest("");
        warmUpBrowser();
        mInitializationListener.get().onSuccess();
    }

    private void createAuthRequest(@Nullable String loginHint) {
        Log.i(TAG, "Creating auth request" +
                (loginHint == null ? "" : ("for login hint: " + loginHint)));
        AuthorizationRequest.Builder authRequestBuilder = new AuthorizationRequest.Builder(
                mAuthStateManager.getCurrent().getAuthorizationServiceConfiguration(),
                mClientId.get(),
                ResponseTypeValues.CODE,
                mConfiguration.getRedirectUri())
                .setScopes(mConfiguration.getScopes());

        if (!TextUtils.isEmpty(loginHint)) {
            authRequestBuilder.setLoginHint(loginHint);
        }

        mAuthRequest.set(authRequestBuilder.build());
    }

    private void warmUpBrowser() {
        mAuthIntentLatch = new CountDownLatch(1);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Warming up browser instance for auth request");
                CustomTabsIntent.Builder intentBuilder =
                        mAuthService.createCustomTabsIntentBuilder(mAuthRequest.get().toUri());
                intentBuilder.setToolbarColor(mCustomTabColor);
                mAuthIntent.set(intentBuilder.build());
                mAuthIntentLatch.countDown();
            }
        });
    }

    @MainThread
    private void handleConfigurationRetrievalResult(AuthorizationServiceConfiguration config,
            AuthorizationException ex) {
        if (config == null) {
            Log.e(TAG, "Failed to retrieve discovery document", ex);
            mInitializationListener.get().onTokenFailure(ex);
            return;
        }

        Log.i(TAG, "Discovery document retrieved");
        mAuthStateManager.replace(new AuthState(config));
        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                initializeClient();
            }
        });
    }

    private void recreateAuthorizationService(Context context) {
        if (mAuthService != null) {
            Log.i(TAG, "Discarding existing AuthService instance");
            mAuthService.dispose();
        }
        mAuthService = createAuthorizationService(context);
        mAuthRequest.set(null);
        mAuthIntent.set(null);
    }

    private AuthorizationService createAuthorizationService(Context context) {
        Log.i(TAG, "Creating authorization service");
        AppAuthConfiguration.Builder builder = new AppAuthConfiguration.Builder();

        return new AuthorizationService(context, builder.build());
    }

    @WorkerThread
    private void doAuth(PendingIntent completionIntent, PendingIntent cancelIntent) {
        try {
            mAuthIntentLatch.await();
        } catch (InterruptedException ex) {
            Log.w(TAG, "Interrupted while waiting for auth intent");
        }

        Log.d(TAG, "Starting authorization flow");
        mAuthService.performAuthorizationRequest(
                mAuthRequest.get(),
                completionIntent,
                cancelIntent,
                mAuthIntent.get());
    }

    @WorkerThread
    private void handleAccessTokenResponse(
            @Nullable TokenResponse tokenResponse,
            @Nullable AuthorizationException authException,
            @NonNull OktaAuthListener listener) {
        mAuthStateManager.updateAfterTokenResponse(tokenResponse, authException);
        if (authException == null) {
            listener.onSuccess();
        } else {
            Log.i(TAG, "Encountered an error with the access token response", authException);
            listener.onTokenFailure(authException);
        }
    }

    private void doAuthorizedAction(
            final String accessToken,
            final String idToken,
            final AuthorizationException ex,
            final BearerAuthRequest action) {
        if (ex != null) {
            Log.e(TAG, "Token refresh failed when performing action", ex);
            action.onTokenFailure(ex);
            return;
        }

        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn;
                try {
                    conn = action.createRequest();
                } catch (Exception e) {
                    Log.e(TAG, "Exception when creating authenticated request", e);
                    action.onFailure(-1, e);
                    return;
                }
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("X-Okta-User-Agent",
                        "Android/" + Build.VERSION.SDK_INT + " " +
                                BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME

                );

                InputStream response;
                try {
                    if (conn.getResponseCode() >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        //4xx and 5xx should be considered failures
                        action.onFailure(conn.getResponseCode(), null);
                    }

                    response = conn.getInputStream();
                } catch (IOException e) {
                    Log.e(TAG, "Exception when adding authorization header to request", e);
                    action.onFailure(-1, e);
                    return;
                }

                action.onSuccess(response);
            }
        });
    }

    /**
     * Listener for OktaAppAuth operations.
     */
    public interface OktaAuthListener {
        /**
         * Called when the operation is successful to allow the caller to be notified.
         */
        void onSuccess();

        /**
         * Called when a failure occurs during the operation related to the authorization flow.
         *
         * @param ex The exception describing the failure
         */
        void onTokenFailure(@NonNull AuthorizationException ex);
    }

    /**
     * Callback for OktaAppAuth operations that return some type of object.
     *
     * @param <T> The type of object which is returned
     */
    public interface OktaAuthActionCallback<T> {
        /**
         * Called when the operation succeeds with the response as the parameter.
         *
         * @param response The response for the operation
         */
        void onSuccess(T response);

        /**
         * Called when a failure occurs during the operation related to the authorization flow.
         *
         * @param ex The exception describing the failure
         */
        void onTokenFailure(@NonNull AuthorizationException ex);

        /**
         * Called when a failure occurs during the operation unrelated to the authorization flow.
         *
         * @param httpResponseCode The 4xx or 5xx HTTP response code received if the operation
         *     involves an HTTP request; {@code -1} otherwise
         * @param ex The exception that caused the failure if one occurred; {@code null} otherwise
         */
        void onFailure(int httpResponseCode, Exception ex);
    }

    /**
     * Interface that allows a caller to construct an HttpURLConnection to a protected endpoint
     * and receive callbacks when the action succeeds or fails. The access token will be
     * automatically refreshed if needed (and if a refresh token exists) and will be added to the
     * "Authorization" header with the standard OAuth 2.0 prefix "Bearer ".
     */
    public interface BearerAuthRequest {

        /**
         * <p>
         * Constructs an HttpURLConnection object that can be used to make an authorized action.
         * The "Authorization" header with the access token will be automatically added to the
         * request with the standard OAuth 2.0 prefix "Bearer ".
         * </p>
         * <p>
         * It is also recommended to call
         * {@link HttpURLConnection#setInstanceFollowRedirects(boolean)} with a value of false
         * for all authorized connections. This does not happen automatically.
         * </p>
         *
         * @return The HttpURLConnection that represents the authorized request
         * @throws Exception Any exception can be thrown in which case
         *     {@link #onFailure(int, Exception)} will be called automatically
         */
        @NonNull
        HttpURLConnection createRequest() throws Exception;

        /**
         * Called when the action succeeds with the response as the parameter.
         *
         * @param response The InputStream with the response for the action
         */
        void onSuccess(@NonNull InputStream response);

        /**
         * Called when a failure occurs during the action related to the authorization flow.
         *
         * @param ex The exception describing the failure
         */
        void onTokenFailure(@NonNull AuthorizationException ex);

        /**
         * Called when a failure occurs during the action unrelated to the authorization flow.
         *
         * @param httpResponseCode The 4xx or 5xx HTTP response code received if the action
         *     involves an HTTP request; {@code -1} otherwise
         * @param ex The exception that caused the failure if one occurred; {@code null} otherwise
         */
        void onFailure(int httpResponseCode, Exception ex);
    }

    /**
     * A TextWatcher that supplies a login hint to the user authentication flow.
     * Use of this handler is optional. After a delay, this handler will warm up
     * the Custom Tabs browser used for authentication with the supplied login hint;
     * the delay avoids constantly re-initializing the browser while the user is typing.
     */
    public static final class LoginHintChangeHandler implements TextWatcher {

        private static final int DEBOUNCE_DELAY_MS = 500;

        private OktaAppAuth mOktaAppAuth;
        private Handler mHandler;
        private RecreateAuthRequestTask mTask;

        /**
         * Constructs a new LoginHintChangeHandler with the OktaAppAuth object that will be used
         * in the call to {@link #login(Context, PendingIntent, PendingIntent)}.
         *
         * @param oktaAppAuth The OktaAppAuth object
         */
        public LoginHintChangeHandler(OktaAppAuth oktaAppAuth) {
            mOktaAppAuth = oktaAppAuth;
            mHandler = new Handler(Looper.getMainLooper());
            mTask = new RecreateAuthRequestTask(oktaAppAuth, "");
        }

        @Override
        public void beforeTextChanged(CharSequence cs, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence cs, int start, int before, int count) {
            mTask.cancel();
            mTask = new RecreateAuthRequestTask(mOktaAppAuth, cs.toString().trim());
            mHandler.postDelayed(mTask, DEBOUNCE_DELAY_MS);
        }

        @Override
        public void afterTextChanged(Editable ed) {}
    }

    /**
     * Responds to changes in the login hint. After a "debounce" delay, warms up the browser
     * for a request with the new login hint; this avoids constantly re-initializing the
     * browser while the user is typing.
     */
    private static final class RecreateAuthRequestTask implements Runnable {

        private final AtomicBoolean mCanceled = new AtomicBoolean();

        private OktaAppAuth mOktaAppAuth;
        private final String mLoginHint;

        private RecreateAuthRequestTask(OktaAppAuth oktaAppAuth, String loginHint) {
            mOktaAppAuth = oktaAppAuth;
            mLoginHint = loginHint;
        }

        @Override
        public void run() {
            if (mCanceled.get()) {
                return;
            }

            mOktaAppAuth.createAuthRequest(mLoginHint);
            mOktaAppAuth.warmUpBrowser();
        }

        public void cancel() {
            mCanceled.set(true);
        }
    }
}
