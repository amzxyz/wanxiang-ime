# Source from a benchmark's checked-out product, not the driver checkout.
# Older pinned revisions used one product flavor; current builds use none.
benchmark_variant=Debug
benchmark_apk_dir=debug
if [[ -d app/src/fx ]]; then
    benchmark_variant=FxDebug
    benchmark_apk_dir=fx/debug
fi
