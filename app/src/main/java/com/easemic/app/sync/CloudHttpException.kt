package com.easemic.app.sync

/**
 * Transport failure talking to the user's R2 bucket (non-2xx HTTP, token
 * refresh refused). Distinct from [EncryptedBackupException], which belongs to
 * the passphrase-sealed backup codec — cloud objects are plaintext by
 * default; an opted-in library instead seals object contents via
 * CloudLibraryGate/PcloCodec, but this exception is about transport, not
 * content, either way.
 */
open class CloudHttpException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
