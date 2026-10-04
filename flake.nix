{
  description = "SNUTT v2 development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    {
      devShells =
        nixpkgs.lib.genAttrs
          [
            "x86_64-linux"
            "aarch64-linux"
            "aarch64-darwin"
          ]
          (system: {
            default = nixpkgs.legacyPackages.${system}.mkShell {
              packages = [ nixpkgs.legacyPackages.${system}.jdk25 ];
            };
          });
    };
}
