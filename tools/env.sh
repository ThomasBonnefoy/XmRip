# Source par xm.sh et par l'app macOS : fixe JAVA_HOME (JDK 17+) s'il est absent.
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null) || JAVA_HOME=
    if [ -z "$JAVA_HOME" ]; then
        for d in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@17 \
                 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
            [ -x "$d/bin/java" ] && JAVA_HOME=$d && break
            [ -x "$d/libexec/openjdk.jdk/Contents/Home/bin/java" ] && JAVA_HOME=$d/libexec/openjdk.jdk/Contents/Home && break
        done
    fi
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi
