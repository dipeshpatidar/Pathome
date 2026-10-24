package com.indore.pathome.spaces.service;

/** Controlled adapter seam. A live Graph implementation remains behind a separate activation gate. */
public interface MetaLeadSourceClient {
    boolean isConfigured();
    String fetchLeadPayload(String pageId, String leadgenId, String formId);
}
