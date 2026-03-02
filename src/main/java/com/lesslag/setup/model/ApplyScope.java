package com.lesslag.setup.model;

/** Whether a patch can be auto-applied or is recommendation-only. */
public enum ApplyScope {
    /** Recommendation-only (server/fork configs). Not auto-applied. */
    RECOMMEND,
    /** Can be applied to LessLag config after user confirmation. */
    LESSLAG_APPLY
}
