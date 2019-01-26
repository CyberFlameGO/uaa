package org.cloudfoundry.identity.uaa.mock.mfa_provider;

import org.apache.commons.lang.ArrayUtils;
import org.cloudfoundry.identity.uaa.TestSpringContext;
import org.cloudfoundry.identity.uaa.mfa.GoogleMfaProviderConfig;
import org.cloudfoundry.identity.uaa.mfa.JdbcMfaProviderProvisioning;
import org.cloudfoundry.identity.uaa.mfa.MfaProvider;
import org.cloudfoundry.identity.uaa.test.HoneycombAuditEventTestListenerExtension;
import org.cloudfoundry.identity.uaa.test.HoneycombJdbcInterceptorExtension;
import org.cloudfoundry.identity.uaa.test.JUnitRestDocumentationExtension;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneSwitchingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.restdocs.ManualRestDocumentation;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.cloudfoundry.identity.uaa.mfa.MfaProvider.MfaProviderType.GOOGLE_AUTHENTICATOR;
import static org.cloudfoundry.identity.uaa.test.SnippetUtils.*;
import static org.cloudfoundry.identity.uaa.util.JsonUtils.serializeExcludingProperties;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.*;
import static org.springframework.restdocs.payload.JsonFieldType.OBJECT;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.templates.TemplateFormats.markdown;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ExtendWith(JUnitRestDocumentationExtension.class)
@ExtendWith(HoneycombJdbcInterceptorExtension.class)
@ExtendWith(HoneycombAuditEventTestListenerExtension.class)
@ActiveProfiles("default")
@WebAppConfiguration
@ContextConfiguration(classes = TestSpringContext.class)
public class MfaProviderEndpointsDocs {

    @Autowired
    private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private TestClient testClient;
    private static final String NAME_DESC = "Human-readable name for this provider. Must be alphanumeric.";
    private static final String ID_DESC = "Unique identifier for this provider. This is a GUID generated by UAA.";
    private static final String CREATED_DESC = "UAA sets the creation date.";
    private static final String LAST_MODIFIED_DESC = "UAA sets the last modification date.";
    private static final String IDENTITY_ZONE_ID_DESC = "Set to the zone that this provider will be active in. Determined either by the Host header or the zone switch header.";
    private static final FieldDescriptor NAME = fieldWithPath("name").required().type(STRING).description(NAME_DESC);
    private static final FieldDescriptor TYPE = fieldWithPath("type").required().type(STRING).description("Type of MFA provider. Available types include `google-authenticator`.");
    private static final FieldDescriptor LAST_MODIFIED = fieldWithPath("last_modified").description(LAST_MODIFIED_DESC);
    private static final FieldDescriptor ID = fieldWithPath("id").type(STRING).description(ID_DESC);
    private static final FieldDescriptor CREATED = fieldWithPath("created").description(CREATED_DESC);
    private static final FieldDescriptor IDENTITY_ZONE_ID = fieldWithPath("identityZoneId").type(STRING).description(IDENTITY_ZONE_ID_DESC);
    private static final HeaderDescriptor IDENTITY_ZONE_ID_HEADER = headerWithName(IdentityZoneSwitchingFilter.HEADER).optional().description("If using a `zones.<zoneId>.admin` scope/token, indicates what zone this request goes to by supplying a zone_id.");
    private static final HeaderDescriptor IDENTITY_ZONE_SUBDOMAIN_HEADER = headerWithName(IdentityZoneSwitchingFilter.SUBDOMAIN_HEADER).optional().description("If using a `zones.<zoneId>.admin` scope/token, indicates what zone this request goes to by supplying a subdomain.");
    private static final HeaderDescriptor MFA_AUTHORIZATION_HEADER = headerWithName("Authorization").description("Bearer token containing `uaa.admin` or `zones.<zoneId>.admin`");
    private FieldDescriptor[] commonProviderFields = {
            NAME,
            TYPE,
    };
    private String adminToken;
    private JdbcMfaProviderProvisioning mfaProviderProvisioning;

    @BeforeEach
    public void setUp(ManualRestDocumentation manualRestDocumentation) throws Exception {
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(springSecurityFilterChain)
                .apply(documentationConfiguration(manualRestDocumentation)
                        .uris().withPort(80).and()
                        .snippets()
                        .withTemplateFormat(markdown()))
                .build();
        testClient = new TestClient(mockMvc);
        adminToken = testClient.getClientCredentialsOAuthAccessToken(
                "admin",
                "adminsecret",
                "");

        mfaProviderProvisioning = webApplicationContext.getBean(JdbcMfaProviderProvisioning.class);
    }

    @Test
    public void testCreateGoogleMfaProvider() throws Exception {
        MfaProvider<GoogleMfaProviderConfig> mfaProvider = getGoogleMfaProvider();

        FieldDescriptor[] idempotentFields = getGoogleMfaProviderFields();
        Snippet requestFields = requestFields(idempotentFields);

        Snippet responseFields = responseFields(getMfaProviderResponseFields(idempotentFields));
        mockMvc.perform(RestDocumentationRequestBuilders.post("/mfa-providers", mfaProvider.getId())
                .accept(APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(serializeExcludingProperties(mfaProvider, "id", "created", "last_modified", "identityZoneId")))
                .andExpect(status().isCreated())
                .andDo(document("{ClassName}/{methodName}",
                        preprocessRequest(prettyPrint()),
                        preprocessResponse(prettyPrint()),
                        requestHeaders(
                                MFA_AUTHORIZATION_HEADER,
                                IDENTITY_ZONE_ID_HEADER
                        ),
                        requestFields,
                        responseFields)
                );
    }

    private FieldDescriptor[] getGoogleMfaProviderFields() {
        return (FieldDescriptor[]) ArrayUtils.addAll(commonProviderFields, new FieldDescriptor[]{
                fieldWithPath("config").optional(null).type(OBJECT).description("Human-readable provider description. Object with optional providerDescription and issue properties."),
                fieldWithPath("config.providerDescription").optional(null).type(STRING).description("Human-readable provider description. Only for backend description purposes."),
                fieldWithPath("config.issuer").optional(null).type(STRING).description("Human-readable tag for display purposes on MFA devices. Defaults to name of identity zone.")
        });
    }

    private FieldDescriptor[] getMfaProviderResponseFields(FieldDescriptor[] idempotentFields) {
        return (FieldDescriptor[]) ArrayUtils.addAll(idempotentFields, new FieldDescriptor[]{
                ID,
                CREATED,
                LAST_MODIFIED,
                IDENTITY_ZONE_ID
        });
    }

    private MfaProvider<GoogleMfaProviderConfig> getGoogleMfaProvider() {
        return (MfaProvider<GoogleMfaProviderConfig>) new MfaProvider<GoogleMfaProviderConfig>()
                    .setName("sampleGoogleMfaProvider"+new RandomValueStringGenerator(6).generate())
                    .setType(GOOGLE_AUTHENTICATOR)
                    .setConfig(new GoogleMfaProviderConfig().setProviderDescription("Google MFA for default zone"));
    }

    @Test
    public void testGetMfaProvider() throws Exception{
        MfaProvider<GoogleMfaProviderConfig> mfaProvider = getGoogleMfaProvider();
        mfaProvider = createMfaProviderHelper(mfaProvider);

        Snippet responseFields = responseFields(getMfaProviderResponseFields(getGoogleMfaProviderFields()));

        ResultActions getMFaResultAction = mockMvc.perform(
                RestDocumentationRequestBuilders.get("/mfa-providers/{id}", mfaProvider.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .accept(APPLICATION_JSON));

        getMFaResultAction.andDo(document(
                "{ClassName}/{methodName}",
                preprocessResponse(prettyPrint()),
                pathParameters(parameterWithName("id").required().description(ID_DESC)),
                requestHeaders(
                        MFA_AUTHORIZATION_HEADER,
                        IDENTITY_ZONE_ID_HEADER
                ),
                responseFields
        ));
    }

    @Test
    public void testListMfaProviders() throws Exception{
        MfaProvider<GoogleMfaProviderConfig> mfaProvider = getGoogleMfaProvider();
        createMfaProviderHelper(mfaProvider);

        Snippet responseFields = responseFields((FieldDescriptor[])
                subFields("[]", getMfaProviderResponseFields(getGoogleMfaProviderFields())));

        ResultActions listMfaProviderAction = mockMvc.perform(RestDocumentationRequestBuilders.get("/mfa-providers")
                .header("Authorization", "Bearer " + adminToken)
                .accept(APPLICATION_JSON));

        listMfaProviderAction.andDo(
            document("{ClassName}/{methodName}",
            preprocessResponse(prettyPrint()),
            requestHeaders(
                    MFA_AUTHORIZATION_HEADER,
                    IDENTITY_ZONE_ID_HEADER),
            responseFields
        ));
    }

    @Test
    public void testDeleteMfaProvider() throws Exception {
        MfaProvider<GoogleMfaProviderConfig> mfaProvider = getGoogleMfaProvider();
        mfaProvider = createMfaProviderHelper(mfaProvider);

        Snippet responseFields = responseFields(getMfaProviderResponseFields(getGoogleMfaProviderFields()));

        ResultActions getMFaResultAction = mockMvc.perform(
                RestDocumentationRequestBuilders.delete("/mfa-providers/{id}", mfaProvider.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .accept(APPLICATION_JSON));

        getMFaResultAction.andDo(document(
                "{ClassName}/{methodName}",
                preprocessResponse(prettyPrint()),
                pathParameters(parameterWithName("id").required().description(ID_DESC)),
                requestHeaders(
                        MFA_AUTHORIZATION_HEADER,
                        IDENTITY_ZONE_ID_HEADER
                ),
                responseFields
        ));
    }

    private MfaProvider createMfaProviderHelper(MfaProvider<GoogleMfaProviderConfig> mfaProvider) throws Exception{
        MockHttpServletResponse createResponse = mockMvc.perform(
                post("/mfa-providers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(JsonUtils.writeValueAsString(mfaProvider))
                        .accept(APPLICATION_JSON)).andReturn().getResponse();
        assertThat(HttpStatus.CREATED.value(), is(createResponse.getStatus()));
        MfaProvider createdMfaProvider = JsonUtils.readValue(createResponse.getContentAsString(), MfaProvider.class);
        return createdMfaProvider;
    }

}
