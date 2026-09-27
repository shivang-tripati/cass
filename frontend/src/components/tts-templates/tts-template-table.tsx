"use client";
import Link from "next/link";
import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, PencilIcon, Trash2Icon, CheckIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { TtsTemplateResponse } from "@/lib/api/contracts";
import type { TtsTemplateSortField } from "@/lib/api/tts-templates";
import { formatDateTime } from "@/lib/format";
import { TtsTemplateStatusBadge } from "@/components/common/tts-template-status-badge";
export interface TtsSort { field: string; direction: "asc" | "desc"; }
interface Props { templates: TtsTemplateResponse[]; sort: TtsSort; onSortChange: (s:TtsSort)=>void; onEdit: (t:TtsTemplateResponse)=>void; onDelete: (t:TtsTemplateResponse)=>void; onApprove: (t:TtsTemplateResponse)=>void; onReject: (t:TtsTemplateResponse)=>void; }
const nextDir = (d:"asc"|"desc")=> d==="asc"?"desc":"asc";
export function TtsTemplateTable({ templates, sort, onSortChange, onEdit, onDelete, onApprove, onReject }: Props){
  function head(field: TtsTemplateSortField, label: string){
    const active = sort.field===field;
    return <Button variant="ghost" size="sm" className="-ml-2 h-7" data-active={active||undefined} onClick={()=>onSortChange({field, direction: active?nextDir(sort.direction):"asc"})}>{label} {active && (sort.direction==="asc"?<ArrowUpIcon/>:<ArrowDownIcon/> )}</Button>;
  }
  const cols: ColumnDef<TtsTemplateResponse>[] = [
    { accessorKey:"name", header:()=>head("name","Name"), cell:({row})=><span className="font-medium">{row.original.name}</span> },
    { accessorKey:"status", header:()=>head("status","Status"), cell:({row})=><TtsTemplateStatusBadge status={row.original.status} /> },
    { accessorKey:"templateText", enableSorting:false, header:"Template", cell:({row})=><span className="text-sm text-muted-foreground truncate max-w-[28ch] block">{row.original.templateText}</span> },
    { accessorKey:"createdAt", header:()=>head("createdAt","Created"), cell:({row})=><span className="text-sm text-muted-foreground whitespace-nowrap">{formatDateTime(row.original.createdAt)}</span> },
    { id:"actions", enableSorting:false, header:()=><span className="sr-only">Actions</span>, cell:({row})=>(
      <div className="flex justify-end gap-1">
        <Button variant="ghost" size="sm" asChild><Link href={`/tts-templates/${row.original.id}`}>View</Link></Button>
        {row.original.status==="PENDING_APPROVAL" && <><Button variant="ghost" size="sm" onClick={()=>onApprove(row.original)}><CheckIcon/>Approve</Button><Button variant="ghost" size="sm" onClick={()=>onReject(row.original)}><XIcon/>Reject</Button></>}
        <Button variant="ghost" size="sm" onClick={()=>onEdit(row.original)}><PencilIcon/>Edit</Button>
        <Button variant="ghost" size="sm" className="text-destructive" onClick={()=>onDelete(row.original)}><Trash2Icon/>Delete</Button>
      </div>
    )},
  ];
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({ data: templates, columns: cols, getCoreRowModel: getCoreRowModel(), manualSorting:true, manualPagination:true });
  return <Table><TableHeader>{table.getHeaderGroups().map(g=><TableRow key={g.id}>{g.headers.map(h=><TableHead key={h.id} aria-sort={h.column.id===sort.field?(sort.direction==="asc"?"ascending":"descending"):undefined}>{h.isPlaceholder?null:flexRender(h.column.columnDef.header, h.getContext())}</TableHead>)}</TableRow>)}</TableHeader><TableBody>{table.getRowModel().rows.map(r=><TableRow key={r.original.id}>{r.getVisibleCells().map(c=><TableCell key={c.id}>{flexRender(c.column.columnDef.cell, c.getContext())}</TableCell>)}</TableRow>)}</TableBody></Table>;
}
