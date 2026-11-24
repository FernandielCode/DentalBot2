package com.example.Project1.Controller;

import com.example.Project1.Config.WhatsAppProperties;
import com.example.Project1.Service.WhatsAppBotService;
import com.example.Project1.Service.WhatsAppSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    @Mock
    private WhatsAppBotService botService;

    @Mock
    private WhatsAppSignatureVerifier signatureVerifier;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        WhatsAppWebhookController controller = new WhatsAppWebhookController(
                new WhatsAppProperties(), botService, signatureVerifier, jsonMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        when(signatureVerifier.isEnabled()).thenReturn(true);
        when(signatureVerifier.isValid(any(byte[].class), any())).thenReturn(false);

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=bad")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(botService);
    }

    @Test
    void processesValidSignature() throws Exception {
        when(signatureVerifier.isEnabled()).thenReturn(true);
        when(signatureVerifier.isValid(any(byte[].class), any())).thenReturn(true);

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
                .andExpect(status().isOk());

        verify(botService).processWebhookEvent(any());
    }

    @Test
    void rejectsMalformedJsonAfterValidSignature() throws Exception {
        when(signatureVerifier.isEnabled()).thenReturn(true);
        when(signatureVerifier.isValid(any(byte[].class), any())).thenReturn(true);

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(botService);
    }

    @Test
    void allowsRequestsWhenVerificationIsDisabled() throws Exception {
        when(signatureVerifier.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
                .andExpect(status().isOk());

        verify(botService).processWebhookEvent(any());
    }
}
