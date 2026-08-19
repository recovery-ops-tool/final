import { useEffect } from 'react';
import { View } from 'react-native';
import { Stack } from 'expo-router';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';
// import * as Sentry from '@sentry/react-native';
import {
  useFonts, Inter_400Regular, Inter_500Medium, Inter_600SemiBold, Inter_700Bold,
} from '@expo-google-fonts/inter';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';
import { AuthProvider, useAuth } from '@/context/AuthContext';
import { LoadingView } from '@/components/ui';
import { useTheme } from '@/theme/useTheme';
import { SosFloatingButton } from '@/components/SosFloatingButton';

SplashScreen.preventAutoHideAsync();

// No-ops until EXPO_PUBLIC_SENTRY_DSN is supplied (dev/CI builds have none)
// -- see eas.json for where a real staging/production DSN should go.
const sentryDsn = process.env.EXPO_PUBLIC_SENTRY_DSN;
// Sentry.init({
//   dsn: sentryDsn,
//   enabled: !!sentryDsn,
//   tracesSampleRate: 1.0,
// });

function RootNavigator() {
  const { user, isLoading } = useAuth();
  const { colors } = useTheme();

  if (isLoading) return <LoadingView />;

  const isAuthenticated = !!user;

  return (
    <View style={{ flex: 1 }}>
      <Stack screenOptions={{
        headerShown: false,
        headerStyle: { backgroundColor: colors.canvas },
        headerTintColor: colors.ink1,
        contentStyle: { backgroundColor: colors.canvas },
      }}
      >
        <Stack.Protected guard={isAuthenticated}>
          <Stack.Screen name="index" />
          <Stack.Screen name="(org)" />
          <Stack.Screen name="(platform)" />
        </Stack.Protected>

        <Stack.Protected guard={!isAuthenticated}>
          <Stack.Screen name="(auth)/login" />
          <Stack.Screen name="(auth)/forgot-password" />
        </Stack.Protected>

        {/* Public screens — reachable both pre- and post-login */}
        <Stack.Screen name="privacy" options={{ headerShown: true, title: 'Privacy Policy', presentation: 'card' }} />
        <Stack.Screen name="terms" options={{ headerShown: true, title: 'Terms of Service', presentation: 'card' }} />
      </Stack>
    </View>
  );
}

import { ErrorBoundary } from '@/components/ErrorBoundary';
import { ToastProvider } from '@/context/ToastContext';
import { SecurityProvider } from '@/context/SecurityContext';

function RootLayout() {
  const scheme = useColorScheme();
  const [fontsLoaded] = useFonts({
    Inter_400Regular, Inter_500Medium, Inter_600SemiBold, Inter_700Bold,
  });

  useEffect(() => {
    if (fontsLoaded) SplashScreen.hideAsync();
  }, [fontsLoaded]);

  if (!fontsLoaded) return null;

  return (
    <SafeAreaProvider>
      <ErrorBoundary>
        <AuthProvider>
          <SecurityProvider>
            <ToastProvider>
              <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
              <RootNavigator />
            </ToastProvider>
          </SecurityProvider>
        </AuthProvider>
      </ErrorBoundary>
    </SafeAreaProvider>
  );
}

export default RootLayout;

