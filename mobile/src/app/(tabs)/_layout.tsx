import { Tabs } from 'expo-router';
import { Image } from 'react-native';
import { Home, Briefcase, Bell, User, ClipboardCheck } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useNotificationsBadge } from '@/hooks/useNotificationsBadge';

export default function TabsLayout() {
  const { colors } = useTheme();
  const unreadCount = useNotificationsBadge();

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.ink3,
        tabBarStyle: { 
          backgroundColor: colors.surface, 
          borderTopColor: colors.border,
          height: 64,
          paddingBottom: 10,
          paddingTop: 8
        },
        tabBarLabelStyle: { fontFamily: 'Inter_500Medium', fontSize: 11 },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ title: 'Home', tabBarIcon: ({ color, size }) => <Home color={color} size={size - 3} /> }}
      />
      <Tabs.Screen
        name="cases"
        options={{ title: 'My Cases', tabBarIcon: ({ color, size }) => <Briefcase color={color} size={size - 3} /> }}
      />
      <Tabs.Screen
        name="visited"
        options={{ title: 'Visited', tabBarIcon: ({ color, size }) => <ClipboardCheck color={color} size={size - 3} /> }}
      />
      <Tabs.Screen
        name="notifications"
        options={{
          title: 'Lucien',
          tabBarIcon: ({ color, size }) => (
            <Image 
              source={require('../../../assets/images/lucien-logo.png')} 
              style={{ 
                width: size - 3, 
                height: size - 3, 
                tintColor: color, 
                resizeMode: 'contain',
                marginTop: 2
              }} 
            />
          ),
          tabBarBadge: unreadCount > 0 ? unreadCount : undefined,
        }}
      />
      <Tabs.Screen
        name="profile"
        options={{ 
          title: 'Profile', 
          tabBarIcon: ({ color, size }) => <User color={color} size={size} />,
          href: null 
        }}
      />
    </Tabs>
  );
}
