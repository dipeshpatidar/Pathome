package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.backoffice.BackofficeDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictJsonRequestDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final StrictJsonRequestDecoder decoder = new StrictJsonRequestDecoder(mapper);

    @Test
    void acceptsOnlyTheTypedSectionFields() throws Exception {
        var value = decoder.decode(mapper.readTree("""
                {"title":"Reviewed title","description":"Exact text","bhkCount":"2BHK",
                 "propertyType":"FLAT"}
                """), BackofficeDtos.Basics.class);
        assertEquals("Reviewed title", value.title());
    }

    @Test
    void rejectsAnInjectedOwnerOrProtectedIdentityField() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(mapper.readTree("""
                {"title":"Reviewed title","description":"Exact text","bhkCount":"2BHK",
                 "propertyType":"FLAT",
                 "ownerUserId":999}
                """), BackofficeDtos.Basics.class));
    }
}

