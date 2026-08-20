import { Tabs } from 'expo-router';
import { LayoutDashboard, Building2, CreditCard, Flag, Sparkles } from 'lucide-react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '@/theme/useTheme';

export default function PlatformTabsLayout() {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.ink3,
        tabBarStyle: { 
          backgroundColor: colors.surface, 
          borderTopColor: colors.border,
          height: 64 + insets.bottom,
          paddingBottom: 10 + insets.bottom,
          paddingTop: 8
        },
        tabBarLabelStyle: { fontFamily: 'Inter_500Medium', fontSize: 11 },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ 
          title: 'Overview', 
          tabBarIcon: ({ color, size }) => <LayoutDashboard color={color} size={size - 3} /> 
        }}
      />
      <Tabs.Screen
        name="setup"
        options={{ 
          title: 'Setup', 
          tabBarIcon: ({ color, size }) => <Building2 color={color} size={size - 3} /> 
        }}
      />
      <Tabs.Screen
        name="billing"
        options={{ 
          title: 'Billing', 
          tabBarIcon: ({ color, size }) => <CreditCard color={color} size={size - 3} /> 
        }}
      />
      <Tabs.Screen
        name="flags"
        options={{ 
          title: 'Flags', 
          tabBarIcon: ({ color, size }) => <Flag color={color} size={size - 3} /> 
        }}
      />
      <Tabs.Screen
        name="lucien"
        options={{ 
          title: 'Lucien', 
          tabBarIcon: ({ color, size }) => <Sparkles color={color} size={size - 3} /> 
        }}
      />
    </Tabs>
  );
}
