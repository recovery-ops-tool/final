import { useEffect } from 'react';
import { View } from 'react-native';
import { Stack } from 'expo-router';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';
import * as Sentry from '@sentry/react-native';
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
Sentry.init({
  dsn: sentryDsn,
  enabled: !!sentryDsn,
  tracesSampleRate: 1.0,
});

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
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="case/[id]/index" options={{ headerShown: true, title: 'Case detail' }} />
          <Stack.Screen
            name="case/[id]/visit"
            options={{ headerShown: true, title: 'Log a visit', presentation: 'modal' }}
          />
          <Stack.Screen
            name="case/[id]/lucien-visit"
            options={{ headerShown: false, presentation: 'fullScreenModal' }}
          />
          <Stack.Screen
            name="case/[id]/ptp"
            options={{ headerShown: true, title: 'Promise to pay', presentation: 'modal' }}
          />
          <Stack.Screen
            name="case/[id]/collection"
            options={{ headerShown: true, title: 'Record collection', presentation: 'modal' }}
          />
          <Stack.Screen
            name="case/[id]/payment-link"
            options={{ headerShown: true, title: 'Send payment link', presentation: 'modal' }}
          />
          <Stack.Screen
            name="sos"
            options={{ headerShown: true, title: '', presentation: 'fullScreenModal' }}
          />
          <Stack.Screen
            name="visit-detail/[id]"
            options={{ headerShown: false, presentation: 'card' }}
          />
        </Stack.Protected>

        <Stack.Protected guard={!isAuthenticated}>
          <Stack.Screen name="(auth)/login" />
          <Stack.Screen name="(auth)/forgot-password" />
        </Stack.Protected>
      </Stack>
      {isAuthenticated ? <SosFloatingButton /> : null}
    </View>
  );
}

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
      <AuthProvider>
        <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
        <RootNavigator />
      </AuthProvider>
    </SafeAreaProvider>
  );
}

export default Sentry.wrap(RootLayout);
