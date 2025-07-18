/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.eclipse.openvsx.cache.CacheService;
import org.eclipse.openvsx.cache.LatestExtensionVersionCacheKeyGenerator;
import org.eclipse.openvsx.eclipse.EclipseService;
import org.eclipse.openvsx.eclipse.TokenService;
import org.eclipse.openvsx.entities.Namespace;
import org.eclipse.openvsx.entities.NamespaceMembership;
import org.eclipse.openvsx.entities.PersonalAccessToken;
import org.eclipse.openvsx.entities.UserData;
import org.eclipse.openvsx.json.*;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.security.OAuth2AttributesConfig;
import org.eclipse.openvsx.security.OAuth2UserServices;
import org.eclipse.openvsx.security.SecurityConfig;
import org.eclipse.openvsx.storage.StorageUtilService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.util.Streamable;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserAPI.class)
@AutoConfigureWebClient
@MockitoBean(types = {
        EclipseService.class, ClientRegistrationRepository.class, StorageUtilService.class, CacheService.class,
        ExtensionValidator.class, SimpleMeterRegistry.class
})
class UserAPITest {

    @MockitoSpyBean
    UserService users;

    @MockitoBean
    EntityManager entityManager;
    
    @MockitoBean
    RepositoryService repositories;

    @Autowired
    MockMvc mockMvc;

    @Test
    void testLoggedIn() throws Exception {
        mockUserData();
        mockMvc.perform(get("/user"))
                .andExpect(status().isOk())
                .andExpect(content().json(userJson(u -> {
                    u.setLoginName("test_user");
                    u.setFullName("Test User");
                    u.setHomepage("http://example.com/test");
                })));
    }

    @Test
    void testNotLoggedIn() throws Exception {
        mockMvc.perform(get("/user"))
                .andExpect(status().isOk())
                .andExpect(content().json(userJson(u -> {
                    u.setError("Not logged in.");
                })));
    }

    @Test
    void testAccessTokens() throws Exception {
        mockAccessTokens();
        mockMvc.perform(get("/user/tokens")
                .with(user("test_user")))
                .andExpect(status().isOk())
                .andExpect(content().json(accessTokensJson(a -> {
                    var t1 = new AccessTokenJson();
                    t1.setDescription("This is token 1");
                    t1.setCreatedTimestamp("2000-01-01T10:00Z");
                    a.add(t1);
                    var t3 = new AccessTokenJson();
                    t3.setDescription("This is token 3");
                    t3.setCreatedTimestamp("2000-01-01T10:00Z");
                    a.add(t3);
                })));
    }

    @Test
    void testAccessTokensNotLoggedIn() throws Exception {
        mockAccessTokens();
        mockMvc.perform(get("/user/tokens"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testCreateAccessToken() throws Exception {
        mockUserData();
        Mockito.doReturn("foobar").when(users).generateTokenValue();
        mockMvc.perform(post("/user/token/create?description={description}", "This is my token")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isCreated())
                .andExpect(content().json(accessTokenJson(t -> {
                    t.setValue("foobar");
                    t.setDescription("This is my token");
                })));
    }

    @Test
    void testCreateAccessTokenNotLoggedIn() throws Exception {
        mockMvc.perform(post("/user/token/create?description={description}", "This is my token")
                .with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDeleteAccessToken() throws Exception {
        var userData = mockUserData();
        var token = new PersonalAccessToken();
        token.setId(100);
        token.setUser(userData);
        token.setActive(true);
        Mockito.when(repositories.findAccessToken(100))
                .thenReturn(token);
        Mockito.when(entityManager.merge(userData))
                .thenReturn(userData);

        mockMvc.perform(post("/user/token/delete/{id}", 100)
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(content().json(successJson("Deleted access token for user test_user.")));
    }

    @Test
    void testDeleteAccessTokenNotLoggedIn() throws Exception {
        mockMvc.perform(post("/user/token/delete/{id}", 100)
                .with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testDeleteAccessTokenInactive() throws Exception {
        var userData = mockUserData();
        var token = new PersonalAccessToken();
        token.setId(100);
        token.setUser(userData);
        token.setActive(false);
        Mockito.when(repositories.findAccessToken(100))
                .thenReturn(token);

        mockMvc.perform(post("/user/token/delete/{id}", 100)
                .with(user("test_user"))
                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(errorJson("Token does not exist.")));
    }

    @Test
    void testDeleteAccessTokenWrongUser() throws Exception {
        mockUserData();
        var userData = new UserData();
        userData.setLoginName("wrong_user");
        var token = new PersonalAccessToken();
        token.setId(100);
        token.setUser(userData);
        token.setActive(true);
        Mockito.when(repositories.findAccessToken(100))
                .thenReturn(token);

        mockMvc.perform(post("/user/token/delete/{id}", 100)
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isNotFound())
                .andExpect(content().json(errorJson("Token does not exist.")));
    }

    @Test
    void testOwnNamespaces() throws Exception {
        mockOwnMemberships();
        mockMvc.perform(get("/user/namespaces")
                .with(user("test_user")))
                .andExpect(status().isOk())
                .andExpect(content().json(namespacesJson(a -> {
                    var ns1 = new NamespaceJson();
                    ns1.setName("foo");
                    a.add(ns1);
                    var ns2 = new NamespaceJson();
                    ns2.setName("bar");
                    a.add(ns2);
                })));
    }

    @Test
    void testOwnNamespacesNotLoggedIn() throws Exception {
        mockOwnMemberships();
        mockMvc.perform(get("/user/namespaces"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testNamespaceMembers() throws Exception {
        mockNamespaceMemberships(NamespaceMembership.ROLE_OWNER);
        mockMvc.perform(get("/user/namespace/{name}/members", "foobar")
                .with(user("test_user")))
                .andExpect(status().isOk())
                .andExpect(content().json(membershipsJson(a -> {
                    var u1 = new UserJson();
                    u1.setLoginName("test_user");
                    var m1 = new NamespaceMembershipJson("foobar", NamespaceMembership.ROLE_OWNER, u1);
                    a.getNamespaceMemberships().add(m1);
                    var u2 = new UserJson();
                    u2.setLoginName("other_user");
                    var m2 = new NamespaceMembershipJson("foobar", NamespaceMembership.ROLE_CONTRIBUTOR, u2);
                    a.getNamespaceMemberships().add(m2);
                })));
    }

    @Test
    void testNamespaceMembersNotLoggedIn() throws Exception {
        mockNamespaceMemberships(NamespaceMembership.ROLE_OWNER);
        mockMvc.perform(get("/user/namespace/{name}/members", "foobar"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testNamespaceMembersNotOwner() throws Exception {
        mockNamespaceMemberships(NamespaceMembership.ROLE_CONTRIBUTOR);
        mockMvc.perform(get("/user/namespace/{name}/members", "foobar")
                .with(user("test_user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void testAddNamespaceMember() throws Exception {
        var userData1 = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");
        Mockito.when(repositories.findNamespace("foobar"))
                .thenReturn(namespace);
        Mockito.when(repositories.isNamespaceOwner(userData1, namespace))
                .thenReturn(true);
        var membership = new NamespaceMembership();
        membership.setUser(userData1);
        membership.setNamespace(namespace);
        membership.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData1, namespace))
                .thenReturn(membership);
        var userData2 = new UserData();
        userData2.setLoginName("other_user");
        Mockito.when(repositories.findUserByLoginName(null, "other_user"))
                .thenReturn(userData2);
        Mockito.when(repositories.findMembership(userData2, namespace))
                .thenReturn(null);

        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "contributor")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(content().json(successJson("Added other_user as contributor of foobar.")));
    }

    @Test
    void testAddNamespaceMemberNotLoggedIn() throws Exception {
        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "contributor")
                .with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testChangeNamespaceMember() throws Exception {
        var userData1 = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");
        Mockito.when(repositories.findNamespace("foobar"))
                .thenReturn(namespace);
        Mockito.when(repositories.isNamespaceOwner(userData1, namespace))
                .thenReturn(true);
        var membership1 = new NamespaceMembership();
        membership1.setUser(userData1);
        membership1.setNamespace(namespace);
        membership1.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData1, namespace))
                .thenReturn(membership1);
        var userData2 = new UserData();
        userData2.setLoginName("other_user");
        Mockito.when(repositories.findUserByLoginName(null, "other_user"))
                .thenReturn(userData2);
        var membership2 = new NamespaceMembership();
        membership2.setUser(userData2);
        membership2.setNamespace(namespace);
        membership2.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData2, namespace))
                .thenReturn(membership2);

        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "contributor")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(content().json(successJson("Changed role of other_user in foobar to contributor.")));
    }

    @Test
    void testRemoveNamespaceMember() throws Exception {
        var userData1 = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");
        Mockito.when(repositories.findNamespace("foobar"))
                .thenReturn(namespace);
        Mockito.when(repositories.isNamespaceOwner(userData1, namespace))
                .thenReturn(true);
        var membership1 = new NamespaceMembership();
        membership1.setUser(userData1);
        membership1.setNamespace(namespace);
        membership1.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData1, namespace))
                .thenReturn(membership1);
        var userData2 = new UserData();
        userData2.setLoginName("other_user");
        Mockito.when(repositories.findUserByLoginName(null, "other_user"))
                .thenReturn(userData2);
        var membership2 = new NamespaceMembership();
        membership2.setUser(userData2);
        membership2.setNamespace(namespace);
        membership2.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData2, namespace))
                .thenReturn(membership2);

        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "remove")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(content().json(successJson("Removed other_user from namespace foobar.")));
    }

    @Test
    void testAddNamespaceMemberNotOwner() throws Exception {
        var userData1 = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");
        Mockito.when(repositories.findNamespace("foobar"))
                .thenReturn(namespace);
        var membership = new NamespaceMembership();
        membership.setUser(userData1);
        membership.setNamespace(namespace);
        membership.setRole(NamespaceMembership.ROLE_CONTRIBUTOR);
        Mockito.when(repositories.findMembership(userData1, namespace))
                .thenReturn(membership);

        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "contributor")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(errorJson("You must be an owner of this namespace.")));
    }

    @Test
    void testChangeNamespaceMemberSameRole() throws Exception {
        var userData1 = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");
        Mockito.when(repositories.findNamespace("foobar"))
                .thenReturn(namespace);
        Mockito.when(repositories.isNamespaceOwner(userData1, namespace))
                .thenReturn(true);
        var membership1 = new NamespaceMembership();
        membership1.setUser(userData1);
        membership1.setNamespace(namespace);
        membership1.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMembership(userData1, namespace))
                .thenReturn(membership1);
        var userData2 = new UserData();
        userData2.setLoginName("other_user");
        Mockito.when(repositories.findUserByLoginName(null, "other_user"))
                .thenReturn(userData2);
        var membership2 = new NamespaceMembership();
        membership2.setUser(userData2);
        membership2.setNamespace(namespace);
        membership2.setRole(NamespaceMembership.ROLE_CONTRIBUTOR);
        Mockito.when(repositories.findMembership(userData2, namespace))
                .thenReturn(membership2);

        mockMvc.perform(post("/user/namespace/{namespace}/role?user={user}&role={role}", "foobar",
                    "other_user", "contributor")
                .with(user("test_user"))
                .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(errorJson("User other_user already has the role contributor.")));
    }


    //---------- UTILITY ----------//

    private UserData mockUserData() {
        var userData = new UserData();
        userData.setLoginName("test_user");
        userData.setFullName("Test User");
        userData.setProviderUrl("http://example.com/test");
        Mockito.doReturn(userData).when(users).findLoggedInUser();
        return userData;
    }

    private String userJson(Consumer<UserJson> content) throws JsonProcessingException {
        var json = new UserJson();
        content.accept(json);
        return new ObjectMapper().writeValueAsString(json);
    }

    private void mockAccessTokens() {
        var userData = mockUserData();
        var token1 = new PersonalAccessToken();
        token1.setUser(userData);
        token1.setValue("token1");
        token1.setDescription("This is token 1");
        token1.setCreatedTimestamp(LocalDateTime.parse("2000-01-01T10:00"));
        token1.setActive(true);
        var token2 = new PersonalAccessToken();
        token2.setUser(userData);
        token2.setValue("token2");
        token2.setDescription("This is token 2");
        token2.setCreatedTimestamp(LocalDateTime.parse("2000-01-01T10:00"));
        token2.setActive(false);
        var token3 = new PersonalAccessToken();
        token3.setUser(userData);
        token3.setValue("token3");
        token3.setDescription("This is token 3");
        token3.setCreatedTimestamp(LocalDateTime.parse("2000-01-01T10:00"));
        token3.setActive(true);
        Mockito.when(repositories.findActiveAccessTokens(userData))
                .thenReturn(Streamable.of(token1, token3));
    }

    private String accessTokenJson(Consumer<AccessTokenJson> content) throws JsonProcessingException {
        var json = new AccessTokenJson();
        content.accept(json);
        return new ObjectMapper().writeValueAsString(json);
    }

    private String accessTokensJson(Consumer<List<AccessTokenJson>> content) throws JsonProcessingException {
        var json = new ArrayList<AccessTokenJson>();
        content.accept(json);
        return new ObjectMapper().writeValueAsString(json);
    }

    private void mockOwnMemberships() {
        var userData = mockUserData();
        var namespace1 = new Namespace();
        namespace1.setName("foo");
        namespace1.setExtensions(Collections.emptyList());
        Mockito.when(repositories.findActiveExtensions(namespace1)).thenReturn(Streamable.empty());
        var membership1 = new NamespaceMembership();
        membership1.setUser(userData);
        membership1.setNamespace(namespace1);
        membership1.setRole(NamespaceMembership.ROLE_OWNER);
        var namespace2 = new Namespace();
        namespace2.setName("bar");
        namespace2.setExtensions(Collections.emptyList());
        Mockito.when(repositories.findActiveExtensions(namespace2)).thenReturn(Streamable.empty());
        var membership2 = new NamespaceMembership();
        membership2.setUser(userData);
        membership2.setNamespace(namespace2);
        membership2.setRole(NamespaceMembership.ROLE_OWNER);
        Mockito.when(repositories.findMemberships(userData))
                .thenReturn(Streamable.of(membership1, membership2));
    }

    private String namespacesJson(Consumer<List<NamespaceJson>> content) throws JsonProcessingException {
        var json = new ArrayList<NamespaceJson>();
        content.accept(json);
        return new ObjectMapper().writeValueAsString(json);
    }

    private void mockNamespaceMemberships(String userRole) {
        var userData = mockUserData();
        var namespace = new Namespace();
        namespace.setName("foobar");

        var membership1 = new NamespaceMembership();
        membership1.setUser(userData);
        membership1.setNamespace(namespace);
        membership1.setRole(userRole);

        var userData2 = new UserData();
        userData2.setLoginName("other_user");
        var membership2 = new NamespaceMembership();
        membership2.setUser(userData2);
        membership2.setNamespace(namespace);
        membership2.setRole(NamespaceMembership.ROLE_CONTRIBUTOR);

        Mockito.when(repositories.findMembershipsForOwner(userData, "foobar"))
                .thenReturn(userRole.equals(NamespaceMembership.ROLE_OWNER) ? List.of(membership1, membership2) : Collections.emptyList());
    }

    private String membershipsJson(Consumer<NamespaceMembershipListJson> content) throws JsonProcessingException {
        var json = new NamespaceMembershipListJson();
        json.setNamespaceMemberships(new ArrayList<>());
        content.accept(json);
        return new ObjectMapper().writeValueAsString(json);
    }

    private String successJson(String message) throws JsonProcessingException {
        var json = ResultJson.success(message);
        return new ObjectMapper().writeValueAsString(json);
    }

    private String errorJson(String message) throws JsonProcessingException {
        var json = ResultJson.error(message);
        return new ObjectMapper().writeValueAsString(json);
    }
    
    @TestConfiguration
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean
        TransactionTemplate transactionTemplate() {
            return new MockTransactionTemplate();
        }

        @Bean
        UserService userService(
                EntityManager entityManager,
                RepositoryService repositories,
                StorageUtilService storageUtil,
                CacheService cache,
                ExtensionValidator validator,
                @Autowired(required = false) ClientRegistrationRepository clientRegistrationRepository,
                OAuth2AttributesConfig attributesConfig
        ) {
            return new UserService(entityManager, repositories, storageUtil, cache, validator, clientRegistrationRepository, attributesConfig);
        }

        @Bean
        OAuth2UserServices oauth2UserServices(
                UserService users,
                TokenService tokens,
                RepositoryService repositories,
                EntityManager entityManager,
                EclipseService eclipse,
                OAuth2AttributesConfig attributesConfig
        ) {
            return new OAuth2UserServices(users, tokens, repositories, entityManager, eclipse, attributesConfig);
        }

        @Bean
        TokenService tokenService(
                TransactionTemplate transactions,
                EntityManager entityManager,
                ClientRegistrationRepository clientRegistrationRepository
        ) {
            return new TokenService(transactions, entityManager, clientRegistrationRepository);
        }

        @Bean
        LatestExtensionVersionCacheKeyGenerator latestExtensionVersionCacheKeyGenerator() {
            return new LatestExtensionVersionCacheKeyGenerator();
        }
    }
}