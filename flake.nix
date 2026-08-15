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
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };
        graalvm = pkgs.graalvmPackages.graalvm-oracle;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            graalvm
            # 이관 원본을 컨테이너에 적재하고 결과를 확인하는 데 쓴다 (docs/migration.md §6)
            pkgs.mongodb-tools
            pkgs.mysql84
          ];

          JAVA_HOME = "${graalvm}";
          GRAALVM_HOME = "${graalvm}";

          shellHook = ''
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "SNUTT v2 — $(java -version 2>&1 | head -n 1)"
          '';
        };
      }
    );
}
