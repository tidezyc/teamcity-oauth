package jetbrains.buildServer.auth.oauth;

import com.google.common.base.Strings;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationResult;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationSchemeAdapter;
import jetbrains.buildServer.serverSide.auth.AuthModuleUtil;
import jetbrains.buildServer.serverSide.auth.ServerPrincipal;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;


public class OAuthAuthenticationScheme extends HttpAuthenticationSchemeAdapter {

    private static final Logger LOG = Logger.getLogger(OAuthAuthenticationScheme.class);
    private static final boolean DEFAULT_ALLOW_CREATING_NEW_USERS_BY_LOGIN = true;
    public static final String CODE = "code";
    public static final String STATE = "state";

    private final PluginDescriptor pluginDescriptor;
    private final ServerPrincipalFactory principalFactory;
    private final OAuthClient authClient;

    public OAuthAuthenticationScheme(@NotNull final PluginDescriptor pluginDescriptor,
                                     @NotNull final ServerPrincipalFactory principalFactory,
                                     @NotNull final OAuthClient authClient) {
        this.pluginDescriptor = pluginDescriptor;
        this.principalFactory = principalFactory;
        this.authClient = authClient;
    }

    @NotNull
    @Override
    protected String doGetName() {
        return PluginConstants.OAUTH_AUTH_SCHEME_NAME;
    }

    @NotNull
    @Override
    public String getDescription() {
        return PluginConstants.OAUTH_AUTH_SCHEME_DESCRIPTION;
    }

    @Nullable
    @Override
    public String getEditPropertiesJspFilePath() {
        return pluginDescriptor.getPluginResourcesPath(PluginConstants.Web.EDIT_SCHEME_PAGE);
    }

    @Nullable
    @Override
    public Collection<String> validate(@NotNull Map<String, String> properties) {
        final Collection<String> errors = new ConfigurationValidator().validate(properties);
        return errors.isEmpty() ? super.validate(properties) : errors;
    }

    @NotNull
    @Override
    public HttpAuthenticationResult processAuthenticationRequest(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Map<String, String> schemeProperties) throws IOException {
        String code = request.getParameter(CODE);
        String state = request.getParameter(STATE);

        if (Strings.isNullOrEmpty(code) || Strings.isNullOrEmpty(state))
            return HttpAuthenticationResult.notApplicable();

        LOG.debug(String.format("oAuth response with code '%s' & state '%s'", code, state));

        if (!state.equals(SessionUtil.getSessionId(request)))
            return sendBadRequest(response, "Marked request as unauthenticated since retrieved 'state' doesn't correspond to current TeamCity session.");

        String token = authClient.getAccessToken(code);
        if (token == null) {
            return sendBadRequest(response, String.format("Marked request as unauthenticated since failed to fetch token for code '%s' and state '%s'.", code, state));
        }

        OAuthUser user = authClient.getUserData(token);
        if (user.getId() == null) {
            return sendBadRequest(response, "Marked request as unauthenticated since user endpoint does not return any login id");
        }
        boolean allowCreatingNewUsersByLogin = AuthModuleUtil.allowCreatingNewUsersByLogin(schemeProperties, DEFAULT_ALLOW_CREATING_NEW_USERS_BY_LOGIN);
        final Optional<ServerPrincipal> principal = principalFactory.getServerPrincipal(user, allowCreatingNewUsersByLogin);

        if (principal.isPresent()) {
            LOG.debug("Request authenticated. Determined user " + principal.get().getName());
            return HttpAuthenticationResult.authenticated(principal.get(), true);
        } else {
            return sendUnauthorizedRequest(response, "Marked request as unauthenticated since user could not be created.");
        }
    }

    private HttpAuthenticationResult sendBadRequest(HttpServletResponse response, String reason) throws IOException {
        LOG.warn(reason);
        response.sendError(HttpStatus.BAD_REQUEST.value(), reason);
        return HttpAuthenticationResult.unauthenticated();
    }

    private HttpAuthenticationResult sendUnauthorizedRequest(HttpServletResponse response, String reason) throws IOException {
        LOG.warn(reason);
        response.sendError(HttpStatus.UNAUTHORIZED.value(), reason);
        return HttpAuthenticationResult.unauthenticated();
    }
}
