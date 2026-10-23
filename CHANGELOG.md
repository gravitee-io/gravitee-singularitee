## [1.9.1](https://github.com/gravitee-io/gravitee-singularitee/compare/1.9.0...1.9.1) (2026-10-23)


### Bug Fixes

* forward request labels to every member of a composite classifier ([5ec02f6](https://github.com/gravitee-io/gravitee-singularitee/commit/5ec02f673be5719c2246a797c71feb42a0b57f7b))
* run ONNX classifiers pinned to batch size 1 one input at a time ([1f9eb63](https://github.com/gravitee-io/gravitee-singularitee/commit/1f9eb634cc56639571293795b223f19e0ec8b223))


### Performance Improvements

* batch classify calls in every engine, ONNX token mode included ([1894257](https://github.com/gravitee-io/gravitee-singularitee/commit/18942579ed968b6f29ff55a1e28b21ba25dc2705))
* send a composite batch to each member as one batch of all pieces ([bb8e367](https://github.com/gravitee-io/gravitee-singularitee/commit/bb8e367a30bc934df6fa9f88d4a630bcb69bace4))

# [1.9.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.8.0...1.9.0) (2026-10-21)


### Bug Fixes

* drop needless trust_remote_code from Qwen3 examples and warn when a model enables it ([2f22adb](https://github.com/gravitee-io/gravitee-singularitee/commit/2f22adb22543b623d91ef6df1c0f7f741c3635e9))
* end a queued sequence that cannot start with start_failed ([5d4c125](https://github.com/gravitee-io/gravitee-singularitee/commit/5d4c125f007d728fefef79ee9bfd75ba56083787))
* exit on failed startup or bad workspace, mark an empty server ready ([08b4048](https://github.com/gravitee-io/gravitee-singularitee/commit/08b4048fb8713854b98b5f93c7f890caf1574474))
* fail requests whose step model is missing or mistyped, add strict workspace load ([fd259c6](https://github.com/gravitee-io/gravitee-singularitee/commit/fd259c687181b91aaee2a0a4fe0296c7027ae1fa))
* format guard scores and memory estimates independent of locale ([f4c9074](https://github.com/gravitee-io/gravitee-singularitee/commit/f4c9074226290aaf8ad8ae6213afb3cc79ffbd86))
* ignore caller context keys that a pipeline writes itself ([3660be3](https://github.com/gravitee-io/gravitee-singularitee/commit/3660be3eda4d20a03578e4dd42a8530d229b7a11))
* keep decision errors client-facing and reserve the decide output ([17c0cbf](https://github.com/gravitee-io/gravitee-singularitee/commit/17c0cbf9e673959449fff1323e7754b138cc2a0a))
* keep relative model paths inside the model cache and includes inside their folder ([07e7fa5](https://github.com/gravitee-io/gravitee-singularitee/commit/07e7fa55f2b6e2ee9699c09630603c7941e1b70e))
* let redact guards skip empty input by default ([6ac4791](https://github.com/gravitee-io/gravitee-singularitee/commit/6ac47911cb3875c0b8892898ed24cbf27496258f))
* log a remote model's missing chat template once at WARN instead of ERROR per request ([41f0ded](https://github.com/gravitee-io/gravitee-singularitee/commit/41f0ded6944305efb6538cd3f09b19c4849f9136))
* make image installs read-only to the runtime user and drop the shared /tmp model dir ([b91f001](https://github.com/gravitee-io/gravitee-singularitee/commit/b91f001cbdefc71dd048c40b47854b5cfe8429fa))
* pass attached media into pipelines with one marker per attachment ([47ed3ab](https://github.com/gravitee-io/gravitee-singularitee/commit/47ed3ab3eacb67fdce82b22e557e007abbb037d7))
* read HTTP bodies only after auth, cap request size, refuse multipart uploads ([1f45365](https://github.com/gravitee-io/gravitee-singularitee/commit/1f45365a1d2a7a9233f59d6482e49963d3af526d))
* refuse caller-supplied LoRA paths on raw Infer unless opted in ([f7f1dfc](https://github.com/gravitee-io/gravitee-singularitee/commit/f7f1dfc2582e00fd0b3a9f4ff689cfe3cb79b299))
* refuse media a model or pipeline cannot read on gRPC Infer and InferPipeline ([138f82e](https://github.com/gravitee-io/gravitee-singularitee/commit/138f82e6ca6045ef0116871482dc5daaa14d9b88))
* refuse remote endpoints that would send Basic credentials over plaintext ([8383f28](https://github.com/gravitee-io/gravitee-singularitee/commit/8383f28bce018c2dee47b96c0d6b7317151c67b3))
* refuse vLLM models and adapters whose only weights are pickle .bin files unless opted in ([6631a5e](https://github.com/gravitee-io/gravitee-singularitee/commit/6631a5e50fcd9f28d8f24f86d943329494569453))
* reserve sub-pipeline context keys and fail a missing sub-pipeline ([db1c14d](https://github.com/gravitee-io/gravitee-singularitee/commit/db1c14dc143250e367a8040db79cd4a88bc1e8f4))
* send gRPC unary errors with a status code and their message ([b1a187d](https://github.com/gravitee-io/gravitee-singularitee/commit/b1a187ded8433f044bc5c69750fb7fdd8a9d9dfa))
* send internal errors as a log reference, fail unstartable sequences ([881f3d0](https://github.com/gravitee-io/gravitee-singularitee/commit/881f3d07c97ca19687f1e9cb627a0f95d0305e78))
* show callers only errors written for them, with a status by kind ([f5d3433](https://github.com/gravitee-io/gravitee-singularitee/commit/f5d34331a74a133cda51231339092b0d2ea1632b))
* stop a download chunk failing during cancel from leaking uncaught ([de19f18](https://github.com/gravitee-io/gravitee-singularitee/commit/de19f1845b8e967eab5cd65982cb7231da0950f9))
* stop sending the HF token to redirect targets on single-stream downloads ([0974e0b](https://github.com/gravitee-io/gravitee-singularitee/commit/0974e0b86a56575ddc77fd9fc0ee3b1ef1d1c5cc))
* treat only localhost and IP literals as loopback ([5af2e29](https://github.com/gravitee-io/gravitee-singularitee/commit/5af2e29c37f7898ce709deacccaf84a7965869f3))
* use a remote's chat template only over TLS with server verification or loopback ([991d991](https://github.com/gravitee-io/gravitee-singularitee/commit/991d99121fe615a937d201021d5fa96fe0908284))


### Features

* guards inspect the whole conversation and reject empty input by default ([bf4fe61](https://github.com/gravitee-io/gravitee-singularitee/commit/bf4fe61310c8a921a9475fca479690cf360478cf))


### Performance Improvements

* classify a guard's targets in one batch call ([8a0df3b](https://github.com/gravitee-io/gravitee-singularitee/commit/8a0df3b1a69d1a086074308627e5dade8ce00c52))

# [1.8.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.7.0...1.8.0) (2026-10-19)


### Features

* decision models ([#13](https://github.com/gravitee-io/gravitee-singularitee/issues/13)) ([5f91142](https://github.com/gravitee-io/gravitee-singularitee/commit/5f91142829ec06590fdf76438af4776ba5afc452))

# [1.7.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.6.1...1.7.0) (2026-09-30)


### Features

* default vLLM LoRA adapter, batched across parallel sequences ([#11](https://github.com/gravitee-io/gravitee-singularitee/issues/11)) ([e7f7a35](https://github.com/gravitee-io/gravitee-singularitee/commit/e7f7a35e68b8e66c8e0caf9e38b57b38802b810f))

## [1.6.1](https://github.com/gravitee-io/gravitee-singularitee/compare/1.6.0...1.6.1) (2026-09-25)


### Bug Fixes

* merge duplicate excludes so dist-onnx and dist-vllm assemble again ([#10](https://github.com/gravitee-io/gravitee-singularitee/issues/10)) ([9dfeb42](https://github.com/gravitee-io/gravitee-singularitee/commit/9dfeb4281244fc345f988aba660d1836be96d9e5))

# [1.6.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.5.1...1.6.0) (2026-09-23)


### Features

* structured output via constrained decoding for response_format ([#9](https://github.com/gravitee-io/gravitee-singularitee/issues/9)) ([9dabae1](https://github.com/gravitee-io/gravitee-singularitee/commit/9dabae199b729021972cb959aaf11ce8897f71a5))

## [1.5.1](https://github.com/gravitee-io/gravitee-singularitee/compare/1.5.0...1.5.1) (2026-09-18)


### Bug Fixes

* attach llama.cpp LoRA adapters to the context so lora_path ([#8](https://github.com/gravitee-io/gravitee-singularitee/issues/8)) ([2a8bf96](https://github.com/gravitee-io/gravitee-singularitee/commit/2a8bf963602d86edb5fac8b83866bb298415ae7d))

# [1.5.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.4.0...1.5.0) (2026-09-17)


### Features

* GliNER4j and llamaj.cpp bump ([#7](https://github.com/gravitee-io/gravitee-singularitee/issues/7)) ([89139bf](https://github.com/gravitee-io/gravitee-singularitee/commit/89139bf6ecfd4009d9f6009452cf99371a7085ba))

# [1.4.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.3.0...1.4.0) (2026-09-07)


### Features

* OpenInference tracing over OTel and generation confidence signals ([7dd5d4e](https://github.com/gravitee-io/gravitee-singularitee/commit/7dd5d4eb4eb4bc55e166689f17df481d75b07c7b))

# [1.3.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.2.1...1.3.0) (2026-08-31)


### Features

* bring gravitee-node-plugins to DAG step execution ([8211cce](https://github.com/gravitee-io/gravitee-singularitee/commit/8211cce4d860d5008286193feaebde927b9bffe7))
* configurable HuggingFace download timeouts and progress interval ([c57b90e](https://github.com/gravitee-io/gravitee-singularitee/commit/c57b90ed5579ee5e5469750b0cd1b8c902aa56b6))

## [1.2.1](https://github.com/gravitee-io/gravitee-singularitee/compare/1.2.0...1.2.1) (2026-08-25)


### Bug Fixes

* remove dead code and deprecated members, tidy deps and config ([dafaee7](https://github.com/gravitee-io/gravitee-singularitee/commit/dafaee75915d025a962f8a47bdd254e974ffdec0))

# [1.2.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.1.0...1.2.0) (2026-08-20)


### Features

* per-entry task/visibility for models & pipelines, plus input-modality reporting ([#3](https://github.com/gravitee-io/gravitee-singularitee/issues/3)) ([7c3d968](https://github.com/gravitee-io/gravitee-singularitee/commit/7c3d968f02676f2a7afeaa70d493aa73daadca95))

# [1.1.0](https://github.com/gravitee-io/gravitee-singularitee/compare/1.0.0...1.1.0) (2026-08-18)


### Features

* plan engine with proof-carry todos, live progress streaming, and hardened runtime ([#1](https://github.com/gravitee-io/gravitee-singularitee/issues/1)) ([f687a5b](https://github.com/gravitee-io/gravitee-singularitee/commit/f687a5bca0b2cae913f34d087870d19ce469c4c8))

# 1.0.0 (2026-08-07)


### Bug Fixes

* do not load native libs in static initializer ([4531b52](https://github.com/gravitee-io/gravitee-singularitee/commit/4531b5229f837f6c5c56c56744be08d70259beb5))
* documentation ([53489bc](https://github.com/gravitee-io/gravitee-singularitee/commit/53489bc3a1ebff9fec818830e3703854b46ce6ee))


### Features

* gravitee singularitee ([d94ca22](https://github.com/gravitee-io/gravitee-singularitee/commit/d94ca221957f211dd583c924c1fc182c65da1eed))
