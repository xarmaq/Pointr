# Contributing to Pointr

Open an issue describing the problem or proposed behavior before starting a large change. Small fixes and documentation improvements can go straight to a pull request.

1. Fork the repository and create a focused branch.
2. Follow the setup in [README.md](README.md).
3. Keep changes scoped and explain the user-visible result.
4. Run the checks described below and include the results in your pull request.

Run `dotnet build windows/Pointr.Server -c Release`, then `gradlew.bat assembleDebug lintDebug` inside `android/`. Check discovery, fresh pairing, reconnection and input on a phone and PC on the same private network. No automated end-to-end test suite is included yet.

Never commit local configuration, recordings, model weights, pairing secrets, signing keys or generated build directories. Use synthetic examples in reports and screenshots.

By submitting a contribution, you agree to license your contribution under the repository MIT license. Third-party assets retain their own licenses.
