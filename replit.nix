{pkgs}: {
  deps = [
    pkgs.openjdk17-bootstrap
    pkgs.jdk17
    pkgs.gradle
    pkgs.wget
    pkgs.unzip
    pkgs.android-tools
  ];
}
