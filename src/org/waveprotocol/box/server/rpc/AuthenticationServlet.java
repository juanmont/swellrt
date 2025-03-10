/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.server.rpc;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gxp.base.GxpContext;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;
import org.waveprotocol.box.server.CoreSettings;
import org.waveprotocol.box.server.authentication.HttpRequestBasedCallbackHandler;
import org.waveprotocol.box.server.authentication.ParticipantPrincipal;
import org.waveprotocol.box.server.authentication.SessionManager;
import org.waveprotocol.box.server.gxp.AuthenticationPage;
import org.waveprotocol.box.server.persistence.AccountStore;
import org.waveprotocol.box.server.robots.agent.welcome.WelcomeRobot;
import org.waveprotocol.box.server.util.RegistrationUtil;
import org.waveprotocol.wave.model.id.WaveIdentifiers;
import org.waveprotocol.wave.model.wave.InvalidParticipantAddress;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.util.logging.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.security.Principal;
import java.security.cert.X509Certificate;

import javax.inject.Singleton;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.x500.X500Principal;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * A servlet for authenticating a user's password and giving them a token via a
 * cookie.
 *
 * @author josephg@gmail.com (Joseph Gentle)
 */
@SuppressWarnings("serial")
@Singleton
public class AuthenticationServlet extends HttpServlet {
  private static final String DEFAULT_REDIRECT_URL = "/";
  public static final String RESPONSE_STATUS_NONE = "NONE";
  public static final String RESPONSE_STATUS_FAILED = "FAILED";
  public static final String RESPONSE_STATUS_SUCCESS = "SUCCESS";
  // The Object ID of the PKCS #9 email address stored in the client certificate.
  // Source: http://www.rsa.com/products/bsafe/documentation/sslc251html/group__AD__COMMON__OIDS.html
  private static final String OID_EMAIL = "1.2.840.113549.1.9.1";

  private static final Log LOG = Log.get(AuthenticationServlet.class);

  private final AccountStore accountStore;
  private final Configuration configuration;
  private final SessionManager sessionManager;
  private final String domain;
  private final boolean isClientAuthEnabled;
  private final String clientAuthCertDomain;
  private final boolean isRegistrationDisabled;
  private final boolean isLoginPageDisabled;
  private boolean failedClientAuth = false;
private final WelcomeRobot welcomeBot;
  private final String analyticsAccount;

  @Inject
  public AuthenticationServlet(AccountStore accountStore,
      Configuration configuration, SessionManager sessionManager,
      @Named(CoreSettings.WAVE_SERVER_DOMAIN) String domain,
      @Named(CoreSettings.ENABLE_CLIENTAUTH) boolean isClientAuthEnabled,
      @Named(CoreSettings.CLIENTAUTH_CERT_DOMAIN) String clientAuthCertDomain,
      @Named(CoreSettings.DISABLE_REGISTRATION) boolean isRegistrationDisabled,
      @Named(CoreSettings.DISABLE_LOGINPAGE) boolean isLoginPageDisabled,
    WelcomeRobot welcomeBot,
      @Named(CoreSettings.ANALYTICS_ACCOUNT) String analyticsAccount) {
    Preconditions.checkNotNull(accountStore, "AccountStore is null");
    Preconditions.checkNotNull(configuration, "Configuration is null");
    Preconditions.checkNotNull(sessionManager, "Session manager is null");
    this.accountStore = accountStore;
    this.configuration = configuration;
    this.sessionManager = sessionManager;
    this.domain = domain.toLowerCase();
    this.isClientAuthEnabled = isClientAuthEnabled;
    this.clientAuthCertDomain = clientAuthCertDomain.toLowerCase();
    this.isRegistrationDisabled = isRegistrationDisabled;
    this.isLoginPageDisabled = isLoginPageDisabled;
    this.welcomeBot = welcomeBot;
    this.analyticsAccount = analyticsAccount;
  }

  @SuppressWarnings("unchecked")
  private LoginContext login(MultiMap<String> parameters) throws IOException, LoginException {
    Subject subject = new Subject();

    CallbackHandler callbackHandler = new HttpRequestBasedCallbackHandler(parameters);

    LoginContext context = new LoginContext("Wave", subject, callbackHandler, configuration);

    // If authentication fails, login() will throw a LoginException.
    context.login();
    return context;

  }

  /**
   * The POST request should have all the fields required for authentication.
   */
  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    req.setCharacterEncoding("UTF-8");
    LoginContext context;
    Subject subject;
    ParticipantId loggedInAddress = null;

    if (isClientAuthEnabled) {
      boolean skipClientAuth = false;
      try {
        X509Certificate[] certs = (X509Certificate[]) req.getAttribute("javax.servlet.request.X509Certificate");

        if (certs == null) {
          if (isLoginPageDisabled) {
            throw new IllegalStateException(
                "No client X.509 certificate provided (you need to get a certificate"
                    + "from your systems manager and import it into your browser).");
          }
          else {
            failedClientAuth = true;
            skipClientAuth = true;
            doGet(req, resp);
          }
        }

        if (!skipClientAuth) {
          failedClientAuth = false;
          subject = new Subject();
          for (X509Certificate cert : certs) {
            X500Principal principal = cert.getSubjectX500Principal();
            subject.getPrincipals().add(principal);
          }
          loggedInAddress = getLoggedInUser(subject);
        }
      } catch (InvalidParticipantAddress e1) {
        throw new IllegalStateException(
            "The user provided valid authentication information, but the username"
                + " isn't a valid user address.");
      }
    }

    if (!isLoginPageDisabled && loggedInAddress == null) {
      try {

          MultiMap<String> parameters;

          try {
            parameters = getRequestParameters(req);
          } catch (CharacterCodingException cce) {
            throw new LoginException("Character coding exception (not utf-8): "
                + cce.getLocalizedMessage());
          } catch (UnsupportedEncodingException uee) {
            throw new LoginException("Bad character encoding specification: "
                + uee.getLocalizedMessage());
          } catch (IOException e) {
            throw new LoginException("Bad parameters: " + e.getLocalizedMessage());
          }

          if (!isAnonymousCredentials(parameters)) {

            context = login(parameters);
            subject = context.getSubject();

            try {
              loggedInAddress = getLoggedInUser(subject);
            } catch (InvalidParticipantAddress e1) {
              throw new IllegalStateException(
                  "The user provided valid authentication information, but the username"
                      + " isn't a valid user address.");
            }

            if (loggedInAddress == null) {
              try {
                context.logout();
              } catch (LoginException e) {
                // Logout failed. Absorb the error, since we're about to throw an
                // illegal state exception anyway.
              }

              throw new IllegalStateException(
                  "The user provided valid authentication information, but we don't "
                      + "know how to map their identity to a wave user address.");
            }

          }

        } catch (LoginException e) {
          String message = "The username or password you entered is incorrect.";
          String responseType = RESPONSE_STATUS_FAILED;
          LOG.info("User authentication failed: " + e.getLocalizedMessage());
          resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
          resp.setContentType("text/html;charset=utf-8");

          if (checkNoRedirect(req)) {
            resp.setContentType("text/plain");
            resp.getWriter().write("login forbidden");
            resp.getWriter().close();
            return;
          }

          AuthenticationPage.write(resp.getWriter(), new GxpContext(req.getLocale()), domain,
              message, responseType, isLoginPageDisabled, analyticsAccount);
          return;
      }

    }


    HttpSession session = req.getSession(true);

    // Anonymous log in
    if (loggedInAddress == null) {
      loggedInAddress = ParticipantId.anonymousOfUnsafe(session.getId(), domain);
    }

    sessionManager.setLoggedInUser(session, loggedInAddress);
    LOG.info("Authenticated user " + loggedInAddress);

    if (checkNoRedirect(req)) {
      resp.setStatus(HttpServletResponse.SC_OK);
      resp.setContentType("text/plain");
      // Return the address in case of auto completed domain
      String jsonResponse =
          "{ \"participantId\" : \"" + loggedInAddress.getAddress() + "\", "
              + " \"sessionId\" : \"" + session.getId() + "\" }";
      resp.getWriter().write(jsonResponse);
      resp.getWriter().close();
    } else
      redirectLoggedInUser(req, resp);
  }

  /**
   * Get the participant id of the given subject.
   *
   * The subject is searched for compatible principals. When other
   * authentication types are added, this method will need to be updated to
   * support their principal types.
   *
   * @throws InvalidParticipantAddress The subject's address is invalid
   */
  private ParticipantId getLoggedInUser(Subject subject) throws InvalidParticipantAddress {
    String address = null;

    for (Principal p : subject.getPrincipals()) {
      // TODO(josephg): When we support other authentication types (LDAP, etc),
      // this method will need to read the address portion out of the other principal types.
      if (p instanceof ParticipantPrincipal) {
        address = ((ParticipantPrincipal) p).getName();
        break;
      } else if (p instanceof X500Principal) {
        return attemptClientCertificateLogin((X500Principal)p);
      }
    }

    return address == null ? null : ParticipantId.of(address);
  }

  /**
   * Attempts to authenticate the user using their client certificate.
   *
   * Retrieves the email from their certificate, using it as the wave username.
   * If the user doesn't exist and registration is enabled, it will automatically create an account
   * before continuing. Otherwise it will simply check if the account exists and authenticate based
   * on that.
   *
   * @throws RuntimeException The encoding of the email is unsupported on this system
   * @throws InvalidParticipantAddress The email address doesn't correspond to an account
   */
  private ParticipantId attemptClientCertificateLogin(X500Principal p)
      throws RuntimeException, InvalidParticipantAddress {
    String distinguishedName = p.getName();
    try {
      LdapName ldapName = new LdapName(distinguishedName);
      for (Rdn rdn: ldapName.getRdns()) {
        if (rdn.getType().equals(OID_EMAIL)) {
          String email = decodeEmailFromCertificate((byte[])rdn.getValue());
          if (email.endsWith("@" + clientAuthCertDomain)) {
            // Check we decoded the string correctly.
            Preconditions.checkState(WaveIdentifiers.isValidIdentifier(email),
                "The decoded email is not a valid wave identifier");
            ParticipantId id = ParticipantId.of(email);
            if (!RegistrationUtil.doesAccountExist(accountStore, id)) {
              if (!isRegistrationDisabled) {
                if (!RegistrationUtil.createAccountIfMissing(accountStore, id, null, welcomeBot)) {
                  return null;
                }
              } else {
                throw new InvalidNameException(
                    "User doesn't already exist, and registration disabled by administrator");
              }
            }
            return id;
          }
        }
      }
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException(ex);
    } catch (InvalidNameException ex) {
      throw new InvalidParticipantAddress(distinguishedName,
          "Certificate does not contain a valid distinguished name");
    }
    return null;
  }

  /**
   * Decodes the user email from the X.509 certificate.
   *
   * Email address is assumed to be valid in ASCII, and less than 128 characters long
   *
   * @param encoded Output from rdn.getValue(). 1st byte is the tag, second is the length.
   * @return The decoded email in ASCII
   * @throws UnsupportedEncodingException The email address wasn't in ASCII
   */
  private String decodeEmailFromCertificate(byte[] encoded) throws UnsupportedEncodingException {
    // Check for < 130, since first 2 bytes are taken up as stated above.
    Preconditions.checkState(encoded.length < 130,"The email address is longer than expected");
    return new String(encoded, 2, encoded.length - 2, "ascii");
  }

  /**
   * On GET, present a login form if the user isn't authenticated.
   */
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    // If the user is already logged in, we'll try to redirect them immediately.
    resp.setCharacterEncoding("UTF-8");
    req.setCharacterEncoding("UTF-8");
    HttpSession session = req.getSession(false);
    ParticipantId user = sessionManager.getLoggedInUser(session);

    if (user != null) {
      redirectLoggedInUser(req, resp);
    } else {
      if (isClientAuthEnabled && !failedClientAuth) {
          X509Certificate[] certs = (X509Certificate[]) req.getAttribute("javax.servlet.request.X509Certificate");
          if (certs != null) {
            doPost(req, resp);
          }
      }

      if (!isLoginPageDisabled) {
        resp.setStatus(HttpServletResponse.SC_OK);
      }
      else {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
      }
      resp.setContentType("text/html;charset=utf-8");
      AuthenticationPage.write(resp.getWriter(), new GxpContext(req.getLocale()), domain, "",
          RESPONSE_STATUS_NONE, isLoginPageDisabled, analyticsAccount);
    }
  }

  /**
   * Redirect the user back to DEFAULT_REDIRECT_URL, unless a custom redirect
   * URL has been specified in the query string; in which case redirect there.
   *
   * Only redirects to local URLs are allowed.
   *
   * @throws IOException
   */
  private void redirectLoggedInUser(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
     Preconditions.checkState(sessionManager.getLoggedInUser(req.getSession(false)) != null,
         "The user is not logged in");
    String query = req.getQueryString();

    // Not using req.getParameter() for this because calling that method might parse the password
    // sitting in POST data into a String, where it could be read by another process after the
    // string is garbage collected.
    if (query == null || !query.startsWith("r=")) {
      resp.sendRedirect(DEFAULT_REDIRECT_URL);
      return;
    }

    String encoded_url = query.substring("r=".length());
    String path = URLDecoder.decode(encoded_url, "UTF-8");

    // The URL must not be an absolute URL to prevent people using this as a
    // generic redirection service.
    URI uri;
    try {
      uri = new URI(path);
    } catch (URISyntaxException e) {
      // The redirect URL is invalid.
      resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    if (Strings.isNullOrEmpty(uri.getHost()) == false) {
      // The URL includes a host component. Disallow it.
      resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } else {
      resp.sendRedirect(path);
    }
  }

  protected boolean checkNoRedirect(HttpServletRequest req) {
    String query = req.getQueryString();
    if (query == null) return false;
    String encoded_url = query.substring("r=".length());
    if (encoded_url == null) return false;
    return encoded_url.equalsIgnoreCase("none");
  }

  private MultiMap<String> getRequestParameters(HttpServletRequest req)
      throws CharacterCodingException, UnsupportedEncodingException, IOException {

    MultiMap<String> parameters = null;

    String parametersLine = req.getReader().readLine();

    // Throws UnsupportedEncodingException.
    byte[] utf8Bytes = parametersLine.getBytes("UTF-8");

    CharsetDecoder utf8Decoder = Charset.forName("UTF-8").newDecoder();
    utf8Decoder.onMalformedInput(CodingErrorAction.IGNORE);
    utf8Decoder.onUnmappableCharacter(CodingErrorAction.IGNORE);

    // Throws CharacterCodingException.
    CharBuffer parsed = utf8Decoder.decode(ByteBuffer.wrap(utf8Bytes));
    parametersLine = parsed.toString();

    parameters = new UrlEncoded(parametersLine);

    return parameters;
  }

  private boolean isAnonymousCredentials(MultiMap<String> parameters) {

    if (parameters.containsKey(HttpRequestBasedCallbackHandler.ADDRESS_FIELD)) {
      String address = parameters.get(HttpRequestBasedCallbackHandler.ADDRESS_FIELD).get(0);

      return ParticipantId.isAnonymousName(address);
    }

    return false;
  }

}
