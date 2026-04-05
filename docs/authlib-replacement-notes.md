# Authlib Replacement Notes

## Purpose

This file collects hard evidence from the authlib patching instrumentation layer. Use it to record real endpoint calls, `YggdrasilEnvironment` behavior, and verified hypotheses (like Boot-Time session injection) before committing patches to the stable branch.

## How To Enable Trace

- Launch the client with JVM property:
  - `-Dely4everyone.authlib-rd.trace=true`

## Expected First Evidence

- Interceptions in `YggdrasilEnvironment` constants.
- Interceptions in `ServicesKeySet` property signatures.
- Interceptions in `isAllowedTextureDomain`.

The first milestone is not “patch everything”. The first milestone is: prove whether the 5 hypotheses (from the Roadmap) succeed or fail.
