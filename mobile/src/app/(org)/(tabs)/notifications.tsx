import { useCallback, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { BellOff, WifiOff, X } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Text, Badge, EmptyState, LoadingView } from '@/components/ui';
import { notificationsApi } from '@/api/notificationsApi';
import { formatDateTime } from '@/utils/date';
import type { ServerNotification } from '@/types/domain';
import { SafeAreaView } from 'react-native-safe-area-context';

const PRIORITY_TONE = { P0: 'error', P1: 'warning', P2: 'accent', P3: 'neutral' } as const;

export default function NotificationsScreen() {
  const { colors, spacing, radius } = useTheme();
  const [items, setItems] = useState<ServerNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const list = await notificationsApi.list();
      setItems(list);
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const onOpen = async (n: ServerNotification) => {
    if (n.readAt) return;
    setItems((prev) => prev.map((i) => (i.id === n.id ? { ...i, readAt: new Date().toISOString() } : i)));
    await notificationsApi.markRead(n.id).catch(() => {});
  };

  const onDismiss = async (n: ServerNotification) => {
    setItems((prev) => prev.filter((i) => i.id !== n.id));
    await notificationsApi.dismiss(n.id).catch(() => {});
  };

  if (loading) return <LoadingView label="Loading alerts…" />;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas }} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, paddingBottom: spacing.s2, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Alerts</Text>
        </View>
        {items.length > 0 ? (
          <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
            <Pressable onPress={async () => {
              const currentItems = [...items];
              setItems([]);
              for (const item of currentItems) {
                await notificationsApi.dismiss(item.id).catch(() => {});
              }
            }}>
              <Text variant="label" color="error">Clear all</Text>
            </Pressable>
          </View>
        ) : null}
      </View>
      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ padding: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
        refreshing={refreshing}
        onRefresh={onRefresh}
        ItemSeparatorComponent={() => <View style={{ height: spacing.s3 }} />}
        renderItem={({ item }) => (
          <Pressable
            onPress={() => onOpen(item)}
            style={{
              flexDirection: 'row', gap: spacing.s3, backgroundColor: colors.surface,
              borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border, padding: spacing.s4,
              opacity: item.readAt ? 0.6 : 1,
            }}
          >
            <View style={{ flex: 1, gap: spacing.s1 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                <Badge tone={PRIORITY_TONE[item.priority]} label={item.priority} />
                {!item.readAt ? <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colors.accent }} /> : null}
              </View>
              <Text variant="bodyMedium">{item.title}</Text>
              {item.body ? <Text variant="caption" color="secondary">{item.body}</Text> : null}
              <Text variant="caption" color="tertiary">{formatDateTime(item.createdAt)}</Text>
            </View>
            <Pressable onPress={() => onDismiss(item)} hitSlop={10} style={{ padding: spacing.s1 }}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          </Pressable>
        )}
        ListEmptyComponent={
          loadError
            ? <EmptyState icon={WifiOff} title="Couldn't load alerts" message="Pull down to try again." />
            : <EmptyState icon={BellOff} title="You're all caught up" message="New alerts about your cases will show up here." />
        }
      />
    </SafeAreaView>
  );
}
