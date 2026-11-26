package com.example.Project1.Model.DTO;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WhatsAppWebhookPayloadTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void parsesTemplateQuickReplyAndContext() throws Exception {
        String json = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "id": "1",
                    "changes": [{
                      "field": "messages",
                      "value": {
                        "messaging_product": "whatsapp",
                        "messages": [{
                          "from": "593911111111",
                          "id": "msg-1",
                          "timestamp": "1",
                          "type": "button",
                          "button": {
                            "payload": "Confirmar",
                            "text": "Confirmar"
                          },
                          "context": {
                            "id": "wamid-reminder-1"
                          }
                        }]
                      }
                    }]
                  }]
                }
                """;

        WhatsAppWebhookPayload payload = jsonMapper.readValue(json, WhatsAppWebhookPayload.class);
        WhatsAppWebhookPayload.IncomingMessage message = payload.entry()
                .get(0)
                .changes()
                .get(0)
                .value()
                .messages()
                .get(0);

        assertNotNull(message.button());
        assertEquals("Confirmar", message.button().payload());
        assertEquals("Confirmar", message.getExtractedText());
        assertEquals("Confirmar", message.getInteractiveSelectionId());
        assertEquals("wamid-reminder-1", message.getContextMessageId());
    }
}
