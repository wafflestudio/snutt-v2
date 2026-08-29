{
  description = "SNUTT v2 development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk25;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            jdk
            pkgs.mongodb-tools
            pkgs.mysql84
          ];

          JAVA_HOME = "${jdk}";

          shellHook = ''
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "SNUTT v2 — $(java -version 2>&1 | head -n 1)"
          '';
        };
      }
    );
}
