import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, ClipboardCheck } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { attendanceApi, type AttendanceRecord } from '@/api/attendanceApi';
import { formatDate } from '@/utils/date';

export default function TeamAttendanceScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const todayIso = new Date().toISOString().split('T')[0];
      const response = await attendanceApi.getByDate(todayIso, 0, 100);
      setRecords(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const filtered = records.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return r.userName?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading team attendance..." />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Team attendance</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search checked-in officers..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => {
            const time = new Date(item.checkedInAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            return (
              <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                    {item.userName}
                  </Text>
                  <Badge tone="success" label="Present" />
                </View>

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="caption" color="secondary">Checked in at: {time}</Text>
                  {item.accuracy ? (
                    <Text variant="caption" color="tertiary">GPS Accuracy: ±{Math.round(item.accuracy)}m</Text>
                  ) : (
                    <Text variant="caption" color="tertiary">No GPS accuracy logged</Text>
                  )}
                </View>
              </Card>
            );
          }}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load log" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={ClipboardCheck}
                title="No check-ins yet"
                message="Officers checking in for their shifts today will show up here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
