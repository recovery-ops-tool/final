import { useCallback, useEffect, useState } from 'react';
import { View, Image, Alert } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as ImagePicker from 'expo-image-picker';
import { router, useFocusEffect } from 'expo-router';
import { Pressable } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { StatusBar } from 'expo-status-bar';
import * as Location from 'expo-location';
import { CalendarCheck, IndianRupee, Handshake, MapPinCheck, CloudOff, RefreshCw, Radio, Bell, ChevronRight, Search } from 'lucide-react-native';
import { useNotificationsBadge } from '@/hooks/useNotificationsBadge';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Button, Card, StatCard, EmptyState, LoadingView, Divider } from '@/components/ui';
import { CaseRow } from '@/components/CaseRow';
import { dailyDispatchApi } from '@/api/dailyDispatchApi';
import { allocationsApi } from '@/api/allocationsApi';
import { attendanceApi } from '@/api/attendanceApi';
import { dashboardApi } from '@/api/dashboardApi';
import { todayIso, formatTime, formatDurationSince } from '@/utils/date';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { extractApiError } from '@/utils/extractApiError';
import { useOfflineSync } from '@/hooks/useOfflineSync';
import { useShiftTracking } from '@/hooks/useShiftTracking';
import { trySync } from '@/utils/offlineQueue';
import type { AllocationResponse, FieldAgentDashboardResponse } from '@/types/domain';

export default function HomeScreen() {
  const { user } = useAuth();
  const { colors, spacing } = useTheme();
  const insets = useSafeAreaInsets();
  const [avatarUri, setAvatarUri] = useState<string | null>(null);
  const unreadCount = useNotificationsBadge();

  useFocusEffect(
    useCallback(() => {
      (async () => {
        try {
          const cached = await AsyncStorage.getItem('user_avatar_uri');
          setAvatarUri(cached);
        } catch (e) {
          console.log('Failed to load avatar from cache', e);
        }
      })();
    }, [])
  );

  const { pending, syncing } = useOfflineSync();
  const { shift, starting, ending, error: shiftError, startShift, endShift } = useShiftTracking();
  const [, forceTick] = useState(0);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [todayCases, setTodayCases] = useState<AllocationResponse[]>([]);
  const [checkedInAt, setCheckedInAt] = useState<string | null>(null);
  const [checkedOutAt, setCheckedOutAt] = useState<string | null>(null);
  const [checkingIn, setCheckingIn] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  const [checkInError, setCheckInError] = useState<string | null>(null);
  const [dashboard, setDashboard] = useState<FieldAgentDashboardResponse | null>(null);

  const load = useCallback(async () => {
    if (!user) return;
    const today = todayIso();

    const casesPromise = dailyDispatchApi.myList(today).catch(async () => {
      const paged = await allocationsApi.getMyCases(user.id, { size: 50 }).catch(() => null);
      return paged?.content ?? [];
    });

    const attendancePromise = attendanceApi.me(today, today).catch(() => []);
    const dashboardPromise = dashboardApi.fieldAgent(user.id).catch(() => null);

    const [cases, attendance, dash] = await Promise.all([casesPromise, attendancePromise, dashboardPromise]);

    setTodayCases(cases);
    setCheckedInAt(attendance[0]?.checkedInAt ?? null);
    setDashboard(dash);
  }, [user]);

  const completedByAllocationId = new Set(
    (dashboard?.todayAssignments ?? []).filter((a) => a.status === 'COMPLETED').map((a) => a.allocationId),
  );

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  useEffect(() => {
    if (!shift) return;
    const t = setInterval(() => forceTick((n) => n + 1), 60_000);
    return () => clearInterval(t);
  }, [shift]);

  const onRefresh = async () => {
    setRefreshing(true);
    await load().catch(() => {});
    setRefreshing(false);
  };

  const onCheckIn = async () => {
    setCheckInError(null);
    setCheckingIn(true);
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      let coords: { lat?: number; lng?: number; accuracy?: number } = {};
      if (status === 'granted') {
        const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
        coords = { lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy ?? undefined };
      }
      const result = await attendanceApi.checkIn(coords);
      setCheckedInAt(result.checkedInAt);
    } catch (e) {
      setCheckInError(extractApiError(e, 'Could not check in. Try again.'));
    } finally {
      setCheckingIn(false);
    }
  };

  const onCheckOut = async () => {
    setCheckingOut(true);
    // Since there is no checkout API yet, simulate network request and set local state
    setTimeout(() => {
      setCheckedOutAt(new Date().toISOString());
      setCheckingOut(false);
    }, 500);
  };

  if (loading) return <LoadingView label="Loading your day…" />;

  return (
    <Screen onRefresh={onRefresh} refreshing={refreshing} padded={false} edges={['left', 'right']}>
      <StatusBar style="dark" />
      
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: insets.top + spacing.s4, paddingBottom: spacing.s2 }}>
        {/* Header: Welcome text on left, Avatar on right */}
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.s6 }}>
          <View>
            <Text variant="body" style={{ color: '#4B5563', marginBottom: 2 }}>Welcome back,</Text>
            <Text
              style={{
                color: '#6B7280',
                fontFamily: 'Inter_700Bold',
                fontSize: 32,
                lineHeight: 40,
                letterSpacing: -0.5
              }}
            >
              {user?.firstName ?? 'Field Officer'}
            </Text>
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            {/* Search Icon */}
            <Pressable
              onPress={() => router.push('/(org)/search')}
              style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: '#F3F4F6', alignItems: 'center', justifyContent: 'center' }}
            >
              <Search size={20} color="#374151" />
            </Pressable>

            {/* Bell Icon */}
            <Pressable
              onPress={() => router.push('/(org)/(tabs)/notifications')}
              style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: '#F3F4F6', alignItems: 'center', justifyContent: 'center' }}
            >
              <Bell size={20} color="#374151" />
              {unreadCount > 0 ? (
                <View style={{ position: 'absolute', top: 8, right: 8, width: 8, height: 8, borderRadius: 4, backgroundColor: '#EF4444', borderWidth: 1.5, borderColor: '#FFFFFF' }} />
              ) : null}
            </Pressable>

            {/* Circular Avatar */}
            <Pressable
              onPress={() => router.push('/(org)/(tabs)/profile')}
              style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: '#0AA550', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}
            >
              {avatarUri ? (
                <Image source={{ uri: avatarUri }} style={{ width: 44, height: 44 }} />
              ) : (
                <Text style={{ color: '#FFFFFF', fontSize: 15, fontWeight: 'bold' }}>
                  {(user?.firstName?.[0] ?? 'F') + (user?.lastName?.[0] ?? 'O')}
                </Text>
              )}
            </Pressable>
          </View>
        </View>

        {pending > 0 ? (
          <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, marginBottom: spacing.s4, backgroundColor: '#FFFFFF', shadowOpacity: 0.05 }}>
            <CloudOff size={18} color={colors.warnBorder} />
            <Text variant="caption" color="secondary" style={{ flex: 1 }}>
              {pending} {pending === 1 ? 'item' : 'items'} waiting to sync
            </Text>
            <Button
              label="Sync now" variant="ghost" fullWidth={false} size="md" loading={syncing} onPress={() => trySync()} icon={<RefreshCw size={14} color={colors.accent} />}
            />
          </Card>
        ) : null}

        {/* Main Action Card */}
        <View style={{ backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.05, shadowRadius: 12, elevation: 3, gap: spacing.s4 }}>
          
          {/* Field Shift */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            <View style={{ width: 48, height: 48, borderRadius: 24, backgroundColor: '#ECFDF5', alignItems: 'center', justifyContent: 'center' }}>
              <Radio size={24} color="#0AA550" />
            </View>

            <View style={{ flex: 1, gap: 4 }}>
              <Text style={{ fontWeight: '700', color: '#111827', fontSize: 16 }}>Field Shift</Text>
              
              <View style={{ flexDirection: 'row' }}>
                {shift ? (
                  <View style={{ backgroundColor: '#ECFDF5', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 }}>
                    <Text style={{ color: '#0AA550', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>Active</Text>
                  </View>
                ) : (
                  <View style={{ backgroundColor: '#F3F4F6', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 }}>
                    <Text style={{ color: '#6B7280', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>OFF</Text>
                  </View>
                )}
              </View>
              {shiftError ? <Text variant="caption" color="error">{shiftError}</Text> : null}
            </View>
            
            {shift ? (
              <Button label="End Shift" variant="outline" onPress={endShift} loading={ending} fullWidth={false} style={{ paddingHorizontal: 20, borderColor: '#0AA550' }} />
            ) : (
              <Button label="Start" onPress={startShift} loading={starting} fullWidth={false} style={{ backgroundColor: '#0AA550', paddingHorizontal: 24 }} />
            )}
          </View>

          <View style={{ height: 1, backgroundColor: '#F3F4F6', marginVertical: 4 }} />

          {/* Daily Check-In */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            <View style={{ width: 48, height: 48, borderRadius: 24, backgroundColor: '#ECFDF5', alignItems: 'center', justifyContent: 'center' }}>
              <MapPinCheck size={24} color="#0AA550" />
            </View>

            <View style={{ flex: 1, gap: 2 }}>
              <Text style={{ fontWeight: '700', color: '#111827', fontSize: 16 }}>Daily Check-In</Text>
              <Text style={{ color: '#6B7280', fontSize: 13 }}>
                {!checkedInAt 
                  ? 'You haven\'t checked in today' 
                  : checkedOutAt 
                    ? `Checked out at ${formatTime(checkedOutAt)}`
                    : `Checked in at ${formatTime(checkedInAt)}`}
              </Text>
              {checkInError ? <Text variant="caption" color="error">{checkInError}</Text> : null}
            </View>

            {!checkedInAt ? (
              <Button label="Check In" onPress={onCheckIn} loading={checkingIn} fullWidth={false} style={{ backgroundColor: '#0AA550', paddingHorizontal: 20 }} />
            ) : !checkedOutAt ? (
              <Button label="Check Out" onPress={onCheckOut} loading={checkingOut} fullWidth={false} variant="outline" style={{ paddingHorizontal: 20, borderColor: '#0AA550' }} />
            ) : null}
          </View>
        </View>

        {/* Stat Cards */}
        <View style={{ flexDirection: 'row', gap: spacing.s3, marginTop: spacing.s5 }}>
          <View style={{ flex: 1, backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.05, shadowRadius: 12, elevation: 3, overflow: 'hidden' }}>
            <View style={{ position: 'absolute', bottom: -30, right: -40, width: '150%', height: 80, borderRadius: 100, backgroundColor: '#F0FDF4', transform: [{ rotate: '-15deg' }] }} />
            <View style={{ width: 36, height: 36, borderRadius: 8, backgroundColor: '#ECFDF5', alignItems: 'center', justifyContent: 'center', marginBottom: spacing.s3 }}>
              <IndianRupee size={20} color="#0AA550" />
            </View>
            <Text style={{ fontSize: 24, fontWeight: '700', color: '#111827' }}>{dashboard ? formatCurrency(dashboard.collectedAmountToday).replace('₹', '₹ ') : '₹ 0'}</Text>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: 4 }}>
              <Text style={{ color: '#6B7280', fontSize: 13 }}>Collected today</Text>
              <Pressable
                onPress={() => router.push('/(org)/(tabs)/cases')}
                style={{ width: 24, height: 24, borderRadius: 12, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 4, elevation: 1 }}
              >
                <ChevronRight size={14} color="#111827" />
              </Pressable>
            </View>
          </View>

          <View style={{ flex: 1, backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.05, shadowRadius: 12, elevation: 3, overflow: 'hidden' }}>
            <View style={{ position: 'absolute', bottom: -30, right: -40, width: '150%', height: 80, borderRadius: 100, backgroundColor: '#F0FDF4', transform: [{ rotate: '-15deg' }] }} />
            <View style={{ width: 36, height: 36, borderRadius: 8, backgroundColor: '#ECFDF5', alignItems: 'center', justifyContent: 'center', marginBottom: spacing.s3 }}>
              <Handshake size={20} color="#0AA550" />
            </View>
            <Text style={{ fontSize: 24, fontWeight: '700', color: '#111827' }}>{dashboard ? String(dashboard.ptpsDueToday) : '0'}</Text>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: 4 }}>
              <Text style={{ color: '#6B7280', fontSize: 13 }}>PTPs due today</Text>
              <Pressable
                onPress={() => router.push('/(org)/ptps')}
                style={{ width: 24, height: 24, borderRadius: 12, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 4, elevation: 1 }}
              >
                <ChevronRight size={14} color="#111827" />
              </Pressable>
            </View>
          </View>
        </View>

        {/* Today's Visits Card */}
        <View style={{ marginTop: spacing.s5, backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.05, shadowRadius: 12, elevation: 3, gap: spacing.s3 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.s2 }}>
            <Text style={{ fontSize: 18, fontWeight: '700', color: '#111827' }}>Today&apos;s visits</Text>
            <Pressable onPress={() => router.push('/(org)/(tabs)/cases')}>
              <Text style={{ fontSize: 14, color: '#0AA550', fontWeight: '500' }}>View all cases</Text>
            </Pressable>
          </View>

          {dashboard && dashboard.todayTotalCases > 0 ? (
            <Text variant="caption" color="secondary">
              {dashboard.todayCompletedCases} of {dashboard.todayTotalCases} done ({Math.round(dashboard.todayCompletionRate)}%)
            </Text>
          ) : null}

          {todayCases.length === 0 ? (
            <View style={{ alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.s5 }}>
              {/* Illustration Placeholder */}
              <View style={{ marginBottom: spacing.s4, alignItems: 'center' }}>
                <View style={{ width: 80, height: 100, backgroundColor: '#ECFDF5', borderRadius: 12, borderWidth: 4, borderColor: '#0AA550', position: 'relative', alignItems: 'center' }}>
                  <View style={{ width: 30, height: 10, backgroundColor: '#0AA550', borderBottomLeftRadius: 4, borderBottomRightRadius: 4, position: 'absolute', top: 0 }} />
                  <View style={{ width: 50, height: 8, backgroundColor: '#CBE1D4', borderRadius: 4, marginTop: 30 }} />
                  <View style={{ width: 50, height: 8, backgroundColor: '#CBE1D4', borderRadius: 4, marginTop: 12 }} />
                  <View style={{ width: 50, height: 8, backgroundColor: '#CBE1D4', borderRadius: 4, marginTop: 12 }} />
                  <View style={{ position: 'absolute', bottom: -10, right: -15, width: 32, height: 32, borderRadius: 16, backgroundColor: '#0AA550', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: '#FFFFFF' }}>
                    <Text style={{ color: '#FFFFFF', fontWeight: 'bold' }}>✓</Text>
                  </View>
                </View>
              </View>

              <Text style={{ fontSize: 16, fontWeight: '700', color: '#111827', marginBottom: 4 }}>No visits scheduled today</Text>
              <Text style={{ fontSize: 13, color: '#6B7280', textAlign: 'center' }}>Cases assigned to you will show up here{'\n'}once dispatched.</Text>
            </View>
          ) : (
            todayCases.map((item) => (
              <CaseRow key={item.id} item={item} completed={completedByAllocationId.has(item.id)} />
            ))
          )}
        </View>
      </View>
    </Screen>
  );
}
