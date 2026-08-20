fn main() {
    if let Err(error) = renderweave_renderer_daemon::run_from_arguments(std::env::args_os()) {
        let exit_code = error.exit_code();
        eprintln!("renderer daemon terminated: {error}");
        std::process::exit(exit_code);
    }
}
