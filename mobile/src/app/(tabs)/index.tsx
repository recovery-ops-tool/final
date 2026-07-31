import { useCallback, useEffect, useState } from 'react';
import { View, Image } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';
import { StatusBar } from 'expo-status-bar';
import * as Location from 'expo-location';
import { CalendarCheck, IndianRupee, Handshake, MapPinCheck, CloudOff, RefreshCw, Radio } from 'lucide-react-native';
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
  const { pending, syncing } = useOfflineSync();
  const { shift, starting, ending, error: shiftError, startShift, endShift } = useShiftTracking();
  const [, forceTick] = useState(0);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [todayCases, setTodayCases] = useState<AllocationResponse[]>([]);
  const [checkedInAt, setCheckedInAt] = useState<string | null>(null);
  const [checkingIn, setCheckingIn] = useState(false);
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

  if (loading) return <LoadingView label="Loading your day…" />;

  return (
    <Screen onRefresh={onRefresh} refreshing={refreshing} padded={false} edges={['left', 'right']}>
      <StatusBar style="dark" backgroundColor="#A7F3D0" />
      {/* Top Banner section with pastel double shade gradient extending under status bar */}
      <LinearGradient
        colors={['#A7F3D0', '#0AA550']}
        start={{ x: 0, y: 0 }}
        end={{ x: 0, y: 1 }}
        style={{ paddingHorizontal: spacing.s4, paddingTop: insets.top + spacing.s4, paddingBottom: spacing.s5, borderBottomLeftRadius: 24, borderBottomRightRadius: 24 }}
      >
        {/* Header row: Logo on the left, Avatar on the right */}
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.s3 }}>
          <Image 
            source={require('../../../assets/images/logo.png')} 
            style={{ width: 120, height: 32, resizeMode: 'contain', marginLeft: -spacing.s5 }} 
          />
          
          {/* Circular Avatar */}
          <View style={{ 
            width: 36, 
            height: 36, 
            borderRadius: 18, 
            backgroundColor: '#065F46', 
            alignItems: 'center', 
            justifyContent: 'center',
            borderWidth: 1,
            borderColor: '#A7F3D0'
          }}>
            <Text style={{ 
              color: '#FFFFFF', 
              fontSize: 14, 
              fontWeight: 'bold' 
            }}>
              {(user?.firstName?.[0] ?? 'F') + (user?.lastName?.[0] ?? 'O')}
            </Text>
          </View>
        </View>

        <View style={{ marginBottom: spacing.s4 }}>
          <Text variant="caption" style={{ color: '#065F46', fontWeight: '500' }}>Welcome back</Text>
          <Text 
            variant="title" 
            style={{ 
              color: '#065F46', 
              fontFamily: 'Inter_700Bold', 
              letterSpacing: 1.5 
            }}
          >
            {user?.firstName ?? 'Field Officer'}
          </Text>
        </View>

        {pending > 0 ? (
          <Card style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, marginBottom: spacing.s4, backgroundColor: '#FFFFFF', borderWidth: 0, shadowOpacity: 0.05 }}>
            <CloudOff size={18} color={colors.warnBorder} />
            <Text variant="caption" color="secondary" style={{ flex: 1 }}>
              {pending} {pending === 1 ? 'item' : 'items'} waiting to sync
            </Text>
            <Button
              label="Sync now"
              variant="ghost"
              fullWidth={false}
              size="md"
              loading={syncing}
              onPress={() => trySync()}
              icon={<RefreshCw size={14} color={colors.accent} />}
            />
          </Card>
        ) : null}

        <Card style={{ gap: spacing.s4, backgroundColor: '#FFFFFF', borderRadius: 20, padding: spacing.s4, shadowOpacity: 0.05, shadowRadius: 10, elevation: 2, borderWidth: 0 }}>
          
          {/* Field Shift Section */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            {/* Themed Icon Container */}
            <View style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: shift ? '#E6F4EA' : '#F1F3F4', alignItems: 'center', justifyContent: 'center' }}>
              <Radio size={20} color={shift ? '#137333' : '#5F6368'} />
            </View>

            <View style={{ flex: 1, gap: 4 }}>
              <Text variant="bodyMedium" style={{ fontWeight: '700', color: '#202124' }}>Field Shift</Text>
              
              <View style={{ flexDirection: 'row' }}>
                {shift ? (
                  <View style={{ backgroundColor: '#E6F4EA', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 12 }}>
                    <Text style={{ color: '#137333', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>Active</Text>
                  </View>
                ) : (
                  <View style={{ backgroundColor: '#F1F3F4', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 12 }}>
                    <Text style={{ color: '#5F6368', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>Off</Text>
                  </View>
                )}
              </View>
              {shiftError ? <Text variant="caption" color="error">{shiftError}</Text> : null}
            </View>
            
            {shift ? (
              <Button label="End Shift" variant="outline" onPress={endShift} loading={ending} fullWidth={false} size="md" style={{ borderColor: '#DADCE0' }} />
            ) : (
              <Button label="Start" onPress={startShift} loading={starting} fullWidth={false} size="md" style={{ backgroundColor: '#0AA550' }} />
            )}
          </View>

          {/* Thin subtle horizontal separator line */}
          <View style={{ height: 1, backgroundColor: '#F1F3F4', marginVertical: 2 }} />

          {/* Attendance Check-in Section */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
            {/* Themed Icon Container */}
            <View style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: checkedInAt ? '#E8F0FE' : '#FCE8E6', alignItems: 'center', justifyContent: 'center' }}>
              <MapPinCheck size={20} color={checkedInAt ? '#1A73E8' : '#D93025'} />
            </View>

            <View style={{ flex: 1, gap: 2 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: '#202124' }}>Daily Check-In</Text>
                {checkedInAt ? (
                  <View style={{ backgroundColor: '#E8F0FE', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 12 }}>
                    <Text style={{ color: '#1A73E8', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>Done</Text>
                  </View>
                ) : (
                  <View style={{ backgroundColor: '#FCE8E6', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 12 }}>
                    <Text style={{ color: '#D93025', fontSize: 10, fontWeight: '700', textTransform: 'uppercase' }}>Pending</Text>
                  </View>
                )}
              </View>
              <Text variant="caption" style={{ color: '#5F6368', fontSize: 12 }}>
                {checkedInAt ? `Checked in at ${formatTime(checkedInAt)}` : 'You haven\'t checked in today'}
              </Text>
              {checkInError ? <Text variant="caption" color="error">{checkInError}</Text> : null}
            </View>

            {!checkedInAt ? (
              <Button label="Check In" onPress={onCheckIn} loading={checkingIn} fullWidth={false} size="md" style={{ backgroundColor: '#1A73E8' }} />
            ) : null}
          </View>
        </Card>
      </LinearGradient>

      {/* Main body content below the green header */}
      <View style={{ padding: spacing.s4, gap: spacing.s5 }}>
        <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
          <StatCard icon={IndianRupee} label="Collected today" value={dashboard ? formatCurrency(dashboard.collectedAmountToday) : '—'} tone="success" />
          <StatCard icon={Handshake} label="PTPs due today" value={dashboard ? String(dashboard.ptpsDueToday) : '—'} tone="warning" />
        </View>

        <View style={{ gap: spacing.s3 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <Text variant="headline">Today's visits</Text>
            <Text variant="caption" color="accent" onPress={() => router.push('/(tabs)/cases')}>View all cases</Text>
          </View>

          {dashboard && dashboard.todayTotalCases > 0 ? (
            <Text variant="caption" color="secondary">
              {dashboard.todayCompletedCases} of {dashboard.todayTotalCases} done ({Math.round(dashboard.todayCompletionRate)}%)
            </Text>
          ) : null}

          {todayCases.length === 0 ? (
            <EmptyState icon={CalendarCheck} title="No visits scheduled today" message="Cases assigned to you will show up here once dispatched." />
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
