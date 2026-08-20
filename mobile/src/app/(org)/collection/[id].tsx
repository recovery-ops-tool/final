import React from 'react';
import { View, ScrollView, SafeAreaView } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { Banknote, ChevronLeft, Calendar, CreditCard, User, FileText, Hash, Building2, Smartphone, Receipt, AlertCircle, CheckCircle2, Clock } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Text, Badge, Divider, Button } from '@/components/ui';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate, formatDateTime } from '@/utils/date';
import type { CollectionResponse } from '@/types/domain';

const getStatusTone = (status: string) => {
  switch (status) {
    case 'APPROVED':
    case 'DEPOSITED':
      return 'success';
    case 'REJECTED':
    case 'CANCELLED':
      return 'error';
    case 'PENDING_APPROVAL':
    default:
      return 'warning';
  }
};

const getStatusIcon = (status: string) => {
  switch (status) {
    case 'APPROVED':
    case 'DEPOSITED':
      return CheckCircle2;
    case 'REJECTED':
    case 'CANCELLED':
      return AlertCircle;
    default:
      return Clock;
  }
};

function DetailRow({ icon: Icon, label, value }: { icon: any; label: string; value?: string | null }) {
  if (!value) return null;
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: 12, paddingVertical: 10 }}>
      <View style={{ width: 32, height: 32, borderRadius: 8, backgroundColor: '#F3F4F6', alignItems: 'center', justifyContent: 'center', marginTop: 2 }}>
        <Icon size={16} color="#6B7280" />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={{ fontSize: 11, color: '#9CA3AF', fontFamily: 'Inter_500Medium', marginBottom: 2 }}>{label}</Text>
        <Text style={{ fontSize: 14, color: '#111827', fontFamily: 'Inter_500Medium' }}>{value}</Text>
      </View>
    </View>
  );
}

export default function CollectionDetailScreen() {
  const { colors, spacing } = useTheme();
  const { item: itemStr } = useLocalSearchParams();
  const router = useRouter();

  let selectedCol: CollectionResponse | null = null;
  if (typeof itemStr === 'string') {
    try {
      selectedCol = JSON.parse(itemStr);
    } catch (e) {}
  }

  if (!selectedCol) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas, paddingTop: 40 }}>
        <View style={{ padding: spacing.s4 }}>
          <Text>Error loading collection details.</Text>
          <Button label="Go Back" onPress={() => router.back()} style={{ marginTop: spacing.s4 }} />
        </View>
      </SafeAreaView>
    );
  }

  const StatusIcon = getStatusIcon(selectedCol.status);
  const amount = (selectedCol as any).amountCollected ?? selectedCol.amount;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#F9FAFB' }}>
      {/* Header */}
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: spacing.s4, paddingVertical: spacing.s3, backgroundColor: '#FFFFFF', borderBottomWidth: 1, borderBottomColor: '#F3F4F6' }}>
        <Button
          variant="ghost"
          icon={<ChevronLeft size={24} color={colors.ink1} />}
          onPress={() => router.back()}
          style={{ padding: 0, marginRight: spacing.s3 }}
        />
        <Text variant="headline">Collection Details</Text>
      </View>

      <ScrollView contentContainerStyle={{ padding: spacing.s4, gap: spacing.s4, paddingBottom: 40 }}>

        {/* Amount hero card */}
        <View style={{ backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s5, alignItems: 'center', gap: spacing.s3, shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.06, shadowRadius: 8, elevation: 2 }}>
          <View style={{ width: 56, height: 56, borderRadius: 28, backgroundColor: '#ECFDF5', alignItems: 'center', justifyContent: 'center' }}>
            <Banknote size={28} color="#0AA550" />
          </View>
          <Text style={{ fontSize: 32, fontWeight: '700', color: '#111827', fontFamily: 'Inter_700Bold' }}>
            {formatCurrency(amount)}
          </Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <StatusIcon size={14} color={selectedCol.status === 'APPROVED' || selectedCol.status === 'DEPOSITED' ? '#0AA550' : selectedCol.status === 'REJECTED' || selectedCol.status === 'CANCELLED' ? '#EF4444' : '#F59E0B'} />
            <Badge label={selectedCol.status.replace('_', ' ')} tone={getStatusTone(selectedCol.status)} />
          </View>
        </View>

        {/* Payment Info */}
        <View style={{ backgroundColor: '#FFFFFF', borderRadius: 16, paddingHorizontal: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.06, shadowRadius: 8, elevation: 2 }}>
          <Text style={{ fontSize: 11, color: '#9CA3AF', fontFamily: 'Inter_600SemiBold', textTransform: 'uppercase', letterSpacing: 0.6, paddingTop: spacing.s4, paddingBottom: spacing.s2 }}>Payment Info</Text>
          <Divider />
          <DetailRow icon={CreditCard} label="Payment Mode" value={selectedCol.paymentMode?.replace(/_/g, ' ')} />
          <DetailRow icon={Calendar} label="Collection Date" value={formatDate(selectedCol.collectionDate || '')} />
          <DetailRow icon={Receipt} label="Receipt Number" value={selectedCol.receiptNumber} />
          <DetailRow icon={Hash} label="Transaction Reference" value={selectedCol.transactionReferenceId} />
          <DetailRow icon={Smartphone} label="UPI Reference" value={selectedCol.upiReferenceId} />
          <DetailRow icon={Hash} label="Cheque Number" value={selectedCol.chequeNumber} />
          <DetailRow icon={Calendar} label="Cheque Date" value={selectedCol.chequeDate ? formatDate(selectedCol.chequeDate) : null} />
          <DetailRow icon={Building2} label="Bank Name" value={selectedCol.bankName} />
        </View>

        {/* People & Dates */}
        <View style={{ backgroundColor: '#FFFFFF', borderRadius: 16, paddingHorizontal: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.06, shadowRadius: 8, elevation: 2 }}>
          <Text style={{ fontSize: 11, color: '#9CA3AF', fontFamily: 'Inter_600SemiBold', textTransform: 'uppercase', letterSpacing: 0.6, paddingTop: spacing.s4, paddingBottom: spacing.s2 }}>Activity</Text>
          <Divider />
          <DetailRow icon={User} label="Submitted By" value={selectedCol.submittedBy} />
          <DetailRow icon={CheckCircle2} label="Approved By" value={selectedCol.approvedBy} />
          <DetailRow icon={Calendar} label="Approved At" value={selectedCol.approvedAt ? formatDateTime(selectedCol.approvedAt) : null} />
          <DetailRow icon={User} label="Deposited By" value={selectedCol.depositedBy} />
          <DetailRow icon={Calendar} label="Deposited At" value={selectedCol.depositedAt ? formatDateTime(selectedCol.depositedAt) : null} />
          <DetailRow icon={Clock} label="Created At" value={formatDateTime(selectedCol.createdAt)} />
        </View>

        {/* Notes */}
        {selectedCol.notes ? (
          <View style={{ backgroundColor: '#FFFFFF', borderRadius: 16, padding: spacing.s4, shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.06, shadowRadius: 8, elevation: 2 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: spacing.s3 }}>
              <FileText size={16} color="#6B7280" />
              <Text style={{ fontSize: 11, color: '#9CA3AF', fontFamily: 'Inter_600SemiBold', textTransform: 'uppercase', letterSpacing: 0.6 }}>Notes</Text>
            </View>
            <Text style={{ fontSize: 14, color: '#374151', fontFamily: 'Inter_400Regular', lineHeight: 20 }}>{selectedCol.notes}</Text>
          </View>
        ) : null}

        {/* Rejection Reason */}
        {selectedCol.rejectionReason ? (
          <View style={{ backgroundColor: '#FEF2F2', borderRadius: 16, padding: spacing.s4, borderWidth: 1, borderColor: '#FECACA' }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: spacing.s3 }}>
              <AlertCircle size={16} color="#EF4444" />
              <Text style={{ fontSize: 11, color: '#EF4444', fontFamily: 'Inter_600SemiBold', textTransform: 'uppercase', letterSpacing: 0.6 }}>Rejection Reason</Text>
            </View>
            <Text style={{ fontSize: 14, color: '#B91C1C', fontFamily: 'Inter_400Regular', lineHeight: 20 }}>{selectedCol.rejectionReason}</Text>
          </View>
        ) : null}

      </ScrollView>
    </SafeAreaView>
  );
}
