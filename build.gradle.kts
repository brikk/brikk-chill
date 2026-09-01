// Root build for brikk-chill.
// Modules:
//   policy          - policy model, string format, and loader
//   policy-painless - base "safe JDK" policy generated from OpenSearch Painless whitelists
//   quarantine      - bytecode verifier (ASM) with validation caching
//   serialize       - safe lambda freeze/thaw (formerly Chillambda)
