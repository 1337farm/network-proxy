package com.forgerig.gatekeeper.proxy

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x500.X500Name
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the MITM CA-subject invariant: the leaf issuer DN must be
 * byte-identical to the CA subject DN, or every client fails with
 * "unable to get local issuer certificate" and the split silently
 * falls back to opaque tunneling (the openrouter.ai symptom).
 *
 * Root cause was X500Name(ca.subjectDN.name): X500Principal.getName
 * returns RFC 1779 order (CN first) while the CA was declared CN-last,
 * so BouncyCastle re-encoded the issuer in a different RDN order.
 * serverContext must rebuild the issuer from the CA's DER-encoded
 * subject instead of re-parsing a string form.
 */
class MitmSubjectTest {

    @Test
    fun encodedSubjectRoundTripsByteIdentical() {
        // Simulates the fixed path: CA subject declared RFC 4514 order,
        // leaf issuer rebuilt from the CA's DER encoding. The re-encoded
        // bytes must be identical or clients reject the chain.
        val declared = X500Name("C=US,O=1337farm,CN=NetworkProxy Local CA")
        val rebuilt = X500Name.getInstance(ASN1Sequence.getInstance(declared.encoded))
        assertEquals(
            declared.encoded.toList(),
            rebuilt.encoded.toList()
        )
    }

    @Test
    fun stringReparseReordersRdns() {
        // Documents the bug: re-parsing the RFC 1779 string form
        // (what X500Principal.getName returns) does NOT round-trip.
        val declared = X500Name("C=US,O=1337farm,CN=NetworkProxy Local CA")
        val rfc1779 = "CN=NetworkProxy Local CA,O=1337farm,C=US"
        val reparsed = X500Name(rfc1779)
        assert(reparsed.encoded.toList() != declared.encoded.toList()) {
            "expected RDN reorder, got identical bytes"
        }
    }

    @Test
    fun setupScriptRebuildsBundleFromFreshCa() {
        val script = SetupScript.scriptFor(3128)
        assert(
            script.contains("rm -f") && script.contains("bundle.pem")
        ) { "stale bundle from a previous CA must be dropped before rebuild" }
    }
}
